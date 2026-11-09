#!/usr/bin/env bash
# Gera o espelho PÚBLICO do fonte (GPLv3) do Nexus Android a partir de UMA tag de release.
# Não publica nada: a saída fica em <saida>/<tag>/ para revisão e publicação pelo responsável do release
# (docs/release-gpl.md).
#
#   scripts/espelho-fonte.sh [--diff-upstream <ref>] [--sdk-fonte <clone> | --sem-terceiros] [--teste] <tag> [saida]
#                                                                          (saida padrão: ../espelho-fonte)
#
#   --sdk-fonte <clone>  clone do linphone-sdk no commit da versão usada, COM submódulos: gera
#                        linphone-sdk-<commit>.tar.gz (fonte correspondente do SDK, AGPLv3) para o Release.
#                        Obrigatório para publicar.
#   --sem-terceiros      só o fonte do app (CI do release, que não alcança o gitlab.linphone.org): gitleaks
#                        obrigatório, sem o tar do SDK, que o responsável do release anexa depois (--sdk-fonte).
#   --teste              gera sem o tar do SDK e sem gitleaks, numa pasta <tag>-TESTE-NAO-PUBLICAR.
#
# - Exporta só a árvore da tag (git archive): sem histórico privado, sem branches, sem .git.
# - Tira os caminhos de scripts/espelho-fonte.excluir; troca o cabeçalho do README (links para docs internos).
# - Gera BUILD-NEXUS.md com os comandos de build da tag (GPLv3 §1: scripts que controlam a compilação).
# - Preenche NOTICE.md (tag, data, commit, SDK); --diff-upstream gera CHANGES.diff contra o upstream.
# - Falha: SDK sem versão exata, link para arquivo excluído, valor secreto/referência interna
#   (espelho-fonte.proibidos, com exceções em espelho-fonte.permitidos), achado do gitleaks ou gitleaks
#   ausente (obrigatório fora do --teste).
# - Cria <saida>/<tag>/repo (git com 1 commit + tag; publicar SÓ a tag), .tar.gz, o tar do SDK e SHA256SUMS.
set -euo pipefail

UPSTREAM=""
SDK_FONTE=""
SEM_TERCEIROS=0
TESTE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --diff-upstream) UPSTREAM="${2:?--diff-upstream <ref>}"; shift 2 ;;
    --sdk-fonte) SDK_FONTE="$(cd "${2:?--sdk-fonte <clone do linphone-sdk>}" && pwd)"; shift 2 ;;
    --sem-terceiros) SEM_TERCEIROS=1; shift ;;
    --teste) TESTE=1; shift ;;
    --*) echo "opção desconhecida: $1" >&2; exit 1 ;;
    *) break ;;
  esac
done
TAG="${1:?uso: scripts/espelho-fonte.sh [--diff-upstream <ref>] [--sdk-fonte <clone> | --sem-terceiros] [--teste] <tag> [saida]}"
[ -n "$SDK_FONTE" ] && [ "$SEM_TERCEIROS" = 1 ] && { echo "--sdk-fonte e --sem-terceiros não combinam" >&2; exit 1; }
RAIZ="$(git rev-parse --show-toplevel)"
SAIDA="${2:-$RAIZ/../espelho-fonte}"
cd "$RAIZ"

git rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null || { echo "tag $TAG não existe" >&2; exit 1; }
COMMIT="$(git rev-parse "$TAG^{commit}")"
DATA="$(git log -1 --format=%cs "$COMMIT")"
DEST="$SAIDA/$TAG"
[ "$TESTE" = 1 ] && DEST="$SAIDA/$TAG-TESTE-NAO-PUBLICAR"
if [ "$TESTE" = 0 ]; then
  command -v gitleaks >/dev/null || { echo "gitleaks não instalado: obrigatório para publicar (use --teste para conferir)" >&2; exit 1; }
  [ -n "$SDK_FONTE" ] || [ "$SEM_TERCEIROS" = 1 ] || { echo "falta --sdk-fonte <clone do linphone-sdk com submódulos> (o Release leva o fonte do SDK)" >&2; exit 1; }
fi
[ -e "$DEST" ] && { echo "$DEST já existe (apague antes de gerar de novo)" >&2; exit 1; }
mkdir -p "$DEST/repo"
ARVORE="$DEST/repo"

# listas da PRÓPRIA tag (a regra vale para a versão publicada)
lista() { git show "$TAG:scripts/$1" 2>/dev/null || cat "scripts/$1"; }
EXCLUIR="$(lista espelho-fonte.excluir)"
PROIBIDOS="$(lista espelho-fonte.proibidos)"
PERMITIDOS="$(lista espelho-fonte.permitidos 2>/dev/null || true)"

git archive --format=tar "$TAG" | tar -x -C "$ARVORE"

shopt -s nullglob dotglob globstar
EXCLUIDOS=()
while IFS= read -r padrao; do
  [[ -z "$padrao" || "$padrao" == \#* ]] && continue
  EXCLUIDOS+=("${padrao%/}")
  for alvo in "$ARVORE"/$padrao "$ARVORE"/**/$padrao; do
    [ -e "$alvo" ] && rm -rf -- "$alvo"
  done
done <<< "$EXCLUIR"
shopt -u nullglob dotglob globstar

for f in LICENSE.txt NOTICE.md TRADEMARKS.md README.md; do
  [ -f "$ARVORE/$f" ] || { echo "falta $f na tag $TAG" >&2; exit 1; }
done

# versão EXATA do SDK (fonte correspondente = commit do linphone-sdk)
SDK="$(sed -n 's/^linphone *= *"\(.*\)"/\1/p' "$ARVORE/gradle/libs.versions.toml" | head -1)"
if [[ -z "$SDK" || "$SDK" == *"+" ]]; then
  echo "linphone-sdk sem versão exata em gradle/libs.versions.toml ('$SDK'): fixe antes do release" >&2; exit 1
fi
SDK_COMMIT="${SDK##*+}"

# README: o cabeçalho do fork (citação no topo) aponta para docs internos -> cabeçalho do espelho
python3 - "$ARVORE/README.md" "$TAG" <<'PY'
import sys
p, tag = sys.argv[1], sys.argv[2]
linhas = open(p, encoding="utf-8").read().split("\n")
i = 0
while i < len(linhas) and (linhas[i].startswith(">") or not linhas[i].strip()):
    i += 1
cab = [
    f"> **Nexus Android {tag} — fonte correspondente (GNU GPLv3).** Fork do",
    "> [linphone-android](https://github.com/BelledonneCommunications/linphone-android) da Belledonne Communications.",
    "> Licenças e componentes: [`NOTICE.md`](NOTICE.md) · marcas: [`TRADEMARKS.md`](TRADEMARKS.md) ·",
    "> como compilar esta versão: [`BUILD-NEXUS.md`](BUILD-NEXUS.md). \"Linphone\" é marca da Belledonne Communications.",
    "",
]
open(p, "w", encoding="utf-8").write("\n".join(cab + linhas[i:]))
PY

# BUILD-NEXUS.md: comandos de build e JDK da própria tag (workflows não vão para o espelho)
{
  echo "# Como compilar o Nexus Android $TAG"
  echo
  echo "Gerado de \`.github/workflows/*.yml\` da tag (o CI não vai para o espelho). Valores \`<...>\` vêm do CI."
  echo
  echo "- JDK: $(git show "$TAG:.github/workflows/release-assinado.yml" | sed -n 's/.*java-version: *"\{0,1\}\([0-9.]*\)"\{0,1\}.*/\1/p' | head -1)"
  echo "- Android SDK: o do Android Gradle Plugin (\`compileSdk\` em \`app/build.gradle.kts\`); linphone-sdk \`$SDK\` (Maven \`download.linphone.org\`)."
  echo
  echo '```sh'
  for wf in apk-teste.yml release-assinado.yml; do
    git show "$TAG:.github/workflows/$wf" 2>/dev/null | grep -E '^\s*run: \./gradlew' | sed -e 's/^\s*run: //' \
      -e 's/\${{[^}]*}}/<valor do CI>/g' -e "s/\"\\\$NEXUS_SOURCE_TAG\"/$TAG/"
  done
  echo '```'
  echo
  echo "Release assinado: crie \`keystore.properties\` com a SUA chave (storeFile, storePassword, keyAlias, keyPassword)."
  echo "O Android instala uma versão modificada assinada com outra chave depois de desinstalar a anterior."
} > "$ARVORE/BUILD-NEXUS.md"

# NOTICE: tag, data, commit, SDK, diff do upstream
if [ -n "$UPSTREAM" ]; then
  git rev-parse -q --verify "$UPSTREAM^{commit}" >/dev/null || { echo "ref do upstream $UPSTREAM não existe" >&2; exit 1; }
  ex=(":(exclude)scripts/espelho-fonte.proibidos" ":(exclude)scripts/espelho-fonte.permitidos")
  for e in "${EXCLUIDOS[@]}"; do ex+=(":(exclude,glob)$e" ":(exclude,glob)**/$e"); done
  git diff "$UPSTREAM" "$TAG" -- . "${ex[@]}" > "$ARVORE/CHANGES.diff"
  DIFF="O diff completo contra o upstream (\`$UPSTREAM\` = $(git rev-parse --short "$UPSTREAM")) está em \`CHANGES.diff\`."
else
  DIFF="Para o diff completo, compare esta árvore com o upstream na versão indicada acima."
fi
sed -i -e "s|@TAG@|$TAG|g" -e "s|@COMMIT@|$COMMIT|g" -e "s|@DATA@|$DATA|g" -e "s|@SDK_VERSION@|$SDK|g" \
  -e "s|@SDK_COMMIT@|$SDK_COMMIT|g" -e "s|@DIFF@|$DIFF|g" "$ARVORE/NOTICE.md"
grep -q '@[A-Z_]*@' "$ARVORE/NOTICE.md" && { echo "NOTICE.md com marcador não preenchido" >&2; exit 1; }

falhou=0
# links para arquivos excluídos (README/NOTICE/docs apontando para o que não está no espelho)
for e in "${EXCLUIDOS[@]}"; do
  [[ "$e" == *"*"* ]] && continue
  if grep -rInF --include='*.md' -- "($e)" "$ARVORE" >/dev/null || grep -rInF --include='*.md' -- "($e/" "$ARVORE" >/dev/null; then
    echo "LINK PARA ARQUIVO EXCLUÍDO ($e):" >&2; grep -rInF --include='*.md' -e "($e)" -e "($e/" "$ARVORE" | head -5 >&2; falhou=1
  fi
done

# valores secretos e referências internas (relpath:linha:conteúdo; exceções em espelho-fonte.permitidos)
while IFS= read -r re; do
  [[ -z "$re" || "$re" == \#* ]] && continue
  achados="$(cd "$ARVORE" && grep -rIniE --exclude="*.svg" -- "$re" . | sed 's|^\./||' \
    | grep -v '^scripts/espelho-fonte\.\(proibidos\|permitidos\):' || true)"
  if [ -n "$achados" ] && [ -n "$PERMITIDOS" ]; then
    achados="$(printf '%s\n' "$achados" | grep -vE -f <(printf '%s\n' "$PERMITIDOS" | grep -v '^#' | grep -v '^$') || true)"
  fi
  if [ -n "$achados" ]; then echo "PROIBIDO ($re):" >&2; printf '%s\n' "$achados" | head -10 >&2; falhou=1; fi
done <<< "$PROIBIDOS"

if command -v gitleaks >/dev/null; then
  gitleaks dir --no-banner "$ARVORE" >&2 || { echo "gitleaks achou segredo" >&2; falhou=1; }
else
  echo "aviso (--teste): gitleaks não instalado; só a lista própria (espelho-fonte.proibidos) foi usada" >&2
fi
[ "$falhou" = 0 ] || { echo "espelho NÃO gerado: corrija na main e faça outra tag" >&2; rm -rf "$DEST"; exit 1; }

git -C "$ARVORE" init -q -b main
git -C "$ARVORE" add -A
git -C "$ARVORE" -c user.name="Grupo IVM" -c user.email="dev@voipmundo.com.br" \
  commit -q -m "Nexus Android $TAG — fonte correspondente (GPLv3)"
git -C "$ARVORE" tag "$TAG"
tar -C "$DEST" --exclude=.git -czf "$DEST/nexus-softphone-android-src-$TAG.tar.gz" --transform "s|^repo|nexus-softphone-android-src-$TAG|" repo

# fonte correspondente do linphone-sdk (AGPLv3): commit fixado + submódulos, para anexar ao Release
if [ -n "$SDK_FONTE" ]; then
  sdk_head="$(git -C "$SDK_FONTE" rev-parse HEAD)"
  [[ "$sdk_head" == "$SDK_COMMIT"* ]] || { echo "--sdk-fonte está em $sdk_head; esperado $SDK_COMMIT" >&2; rm -rf "$DEST"; exit 1; }
  if git -C "$SDK_FONTE" submodule status --recursive | grep -q '^[-+U]'; then
    echo "--sdk-fonte com submódulo ausente ou fora do commit fixado (git submodule update --init --recursive)" >&2
    rm -rf "$DEST"; exit 1
  fi
  pref="linphone-sdk-$sdk_head"
  mkdir -p "$DEST/sdk.tmp"
  git -C "$SDK_FONTE" archive --prefix="$pref/" HEAD | tar -x -C "$DEST/sdk.tmp"
  git -C "$SDK_FONTE" submodule foreach --quiet --recursive \
    "git archive --prefix=\"$pref/\$displaypath/\" HEAD | tar -x -C \"$DEST/sdk.tmp\""
  tar -C "$DEST/sdk.tmp" -czf "$DEST/$pref.tar.gz" "$pref"
  rm -rf "$DEST/sdk.tmp"
fi
( cd "$DEST" && sha256sum ./*.tar.gz > SHA256SUMS )

echo "OK: $DEST"
echo "  SDK $SDK -> https://github.com/BelledonneCommunications/linphone-sdk/tree/$SDK_COMMIT"
[ "$TESTE" = 1 ] && { echo "TESTE: não publicar (sem tar do SDK/gitleaks obrigatórios)"; exit 0; }
[ "$SEM_TERCEIROS" = 1 ] && echo "SEM o tar do linphone-sdk: antes de distribuir, gere com --sdk-fonte e anexe ao Release"
echo "Publicar SÓ a tag (nunca push/force na main do espelho):"
echo "  git -C '$ARVORE' push <url do nexus-softphone-android-src> refs/tags/$TAG"
echo "  Release da tag: anexar $(cd "$DEST" && ls ./*.tar.gz SHA256SUMS | tr '\n' ' ')"
