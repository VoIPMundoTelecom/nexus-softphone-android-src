/*
 * Copyright (c) 2026 Grupo IVM (Nexus / Voipmundo).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Compartilhar logs" do beta: junta o log do core (arquivos `linphone*.log` da coleta de logs), mascara
 * credenciais linha a linha e grava um `.zip` local para o share sheet. Nada vai para servidor: quem testa escolhe
 * para onde manda. Regras puras, para teste de unidade.
 *
 * Mascarado: senha/ha1/segredo/token em qualquer forma comum (`chave=valor`, `chave: valor`, JSON, `<entry
 * name="passwd">` do provisionamento, `<passwd>` do XML de conta), `response`/`cnonce` do Digest (o `response` permite
 * ataque de dicionário offline à senha), `Bearer`/`Basic`, JWT, token de push (`pn-prid`), o token do link de
 * provisionamento (`/prov/<token>`) e segmentos de URL com cara de segredo (32+ caracteres aleatórios).
 * Fica (é o que a triagem precisa): métodos e respostas SIP, usuário, realm, nonce, hosts, números e horários.
 */
object LogCompartilhado {
    const val MASCARA = "***"

    /** Teto do texto sem compressão; acima disso os arquivos mais velhos ficam de fora (o mais novo sempre entra). */
    const val MAX_BYTES_TEXTO = 32L * 1024 * 1024

    const val NOME_TEXTO = "log.txt"

    private val ARQUIVO_DE_LOG = Regex("^linphone.*\\.log$")

    /** Linha maior que isto é cortada antes de mascarar (SIP enorme ou lixo binário não trava a geração). */
    const val MAX_CHARS_LINHA = 16 * 1024

    /** Palavras que, dentro de um nome de campo, indicam segredo (sem diferenciar maiúsculas). */
    private const val SECRETO =
        "passw(?:or)?d|senha|(?<![a-z0-9])ha1|secret|segredo|token|api[_-]?key|private[_-]?key|" +
            "code[_-]?verifier|pn-prid|pwd"

    /** Senha propriamente dita: sem aspas, o valor vai até o fim da linha (pode ter espaço ou `;`). */
    private const val SENHA = "passw(?:or)?d|senha|(?<![a-z0-9])ha1|secret|segredo|pwd"

    /**
     * Nome de campo inteiro que contém uma das [palavras]: começa fora de palavra, até 64 caracteres, possessivo, e
     * a palavra é conferida num lookahead limitado. Nada de `[\w.-]*` solto antes da palavra (a regex voltava atrás
     * em cubo numa linha longa) e nome que não é segredo não consome o resto da linha.
     */
    private fun nome(palavras: String) = "(?<![\\w.-])${nomeSemBorda(palavras)}"

    private fun nomeSemBorda(palavras: String) = "((?=[\\w.-]{0,63}(?:$palavras))[\\w.-]{1,64}+)"

    private val OPCOES = setOf(RegexOption.IGNORE_CASE)

    private fun regra(regex: String, troca: (MatchResult) -> String?) =
        Regex(regex, OPCOES) to { m: MatchResult -> troca(m) ?: m.value }

    private val REGRAS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        // XML do provisionamento: <entry overwrite="true" name="passwd">segredo</entry> (qualquer ordem, ' ou ")
        regra("(<entry\\b[^>]*?\\bname\\s*=\\s*[\"']${nomeSemBorda(SECRETO)}[\"'][^>]*>)[^<]*(</entry>)") { m ->
            "${m.groupValues[1]}$MASCARA${m.groupValues[3]}"
        },
        // XML simples: <passwd>segredo</passwd>
        regra("(<${nomeSemBorda(SECRETO)}(?:\\s[^>]*)?>)[^<]*(</\\2>)") { m ->
            "${m.groupValues[1]}$MASCARA${m.groupValues[3]}"
        },
        // JSON, também escapado dentro de outro JSON: "token": "x"  /  {\"password\":\"x\"}
        regra("(\\\\?\"${nomeSemBorda(SECRETO)}\\\\?\"\\s*:\\s*(\\\\?\"))((?:[^\"\\\\]|\\\\[^\"])*+)(\\\\?\")") { m ->
            "${m.groupValues[1]}$MASCARA${m.groupValues[5]}"
        },
        // Digest: response="..." e cnonce="..." (o resto do Authorization fica para a triagem)
        regra("(\\b(?:response|cnonce)\\s*=\\s*)(\"[^\"]*\"|[^,\\s;]+)") { m ->
            val v = m.groupValues[2]
            m.groupValues[1] + if (v.startsWith("\"")) "\"$MASCARA\"" else MASCARA
        },
        // Cookie inteiro
        regra("\\b((?:Set-)?Cookie\\s*:\\s*).+") { m -> "${m.groupValues[1]}$MASCARA" },
        // SRTP SDES: a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:<chave>
        regra("(inline:)[^|\\s]+") { m -> "${m.groupValues[1]}$MASCARA" },
        // senha sem aspas: chave=valor até o fim da linha
        regra("${nome(SENHA)}(\\s*[=:]\\s*)(?![\"'])\\S.*") { m -> "${m.groupValues[1]}${m.groupValues[2]}$MASCARA" },
        // chave=valor, chave: valor (linphonerc, URL, cabeçalho, parâmetro SIP)
        regra("${nome(SECRETO)}(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&;,\"'<>\\[\\]]+)") { m ->
            val v = m.groupValues[3]
            m.groupValues[1] + m.groupValues[2] + when {
                v.startsWith("\"") -> "\"$MASCARA\""
                v.startsWith("'") -> "'$MASCARA'"
                else -> MASCARA
            }
        },
        // estilo dos logs do app: token [x], "recovery token received [x]", "Password is [x]"
        regra("${nome(SECRETO)}((?:\\s++[\\w-]{1,32}+){0,3}\\s*+\\[)[^\\]]*(\\])") { m ->
            "${m.groupValues[1]}${m.groupValues[2]}$MASCARA${m.groupValues[3]}"
        }
    ) + listOf(
        // sem IGNORE_CASE: "Basic settings" não é credencial
        Regex("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}") to { m -> "${m.groupValues[1]} $MASCARA" },
        Regex("\\beyJ[\\w-]{5,}\\.[\\w-]{5,}\\.[\\w-]*") to { _ -> MASCARA },
        // token de push e afins soltos no texto (100+ caracteres sem espaço)
        Regex("[A-Za-z0-9_:.-]{100,}") to { _ -> MASCARA },
        // link de provisionamento do givm: /prov/<token>
        Regex("(/prov/)[^/\\s?#\"'<>\\]\\[)]+", OPCOES) to { m -> "${m.groupValues[1]}$MASCARA" },
        // segmento de URL ou valor de query com cara de segredo (32+ caracteres sem separador); branch/tag/Call-ID
        // do SIP ficam (servidor PJSIP usa UUID)
        Regex(
            "(?<=[/=])(?<!branch=)(?<!tag=)(?<!call-id=)(?<!uuid=)[A-Za-z0-9_-]{32,}+(?=[/?&#\\s\"'<>\\]\\[;),]|$)",
            OPCOES
        ) to { _ -> MASCARA }
    )

    fun mascarar(linha: String): String {
        val l = if (linha.length > MAX_CHARS_LINHA) linha.take(MAX_CHARS_LINHA) + " [linha cortada]" else linha
        return REGRAS.fold(l) { acc, (regex, troca) -> regex.replace(acc, troca) }
    }

    /** Arquivos da coleta de logs do core, do mais velho para o mais novo. */
    fun arquivosDeLog(pasta: File): List<File> =
        pasta.listFiles { f -> f.isFile && ARQUIVO_DE_LOG.matches(f.name) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenByDescending { it.name })
            .orEmpty()

    /** Os mais novos que cabem em [maxBytes] (o mais novo sempre), ainda do mais velho para o mais novo. */
    fun dentroDoTeto(arquivos: List<File>, maxBytes: Long = MAX_BYTES_TEXTO): List<File> {
        val out = ArrayDeque<File>()
        var total = 0L
        for (f in arquivos.asReversed()) {
            if (out.isNotEmpty() && total + f.length() > maxBytes) break
            out.addFirst(f)
            total += f.length()
        }
        return out.toList()
    }

    /**
     * Grava em [destino] um zip com [NOME_TEXTO]: [cabecalho] e depois cada arquivo, linha a linha, mascarado.
     * Devolve o número de linhas de log escritas. [ativo] falso (tela fechada) interrompe com CancellationException.
     */
    fun gerar(arquivos: List<File>, destino: File, cabecalho: String, ativo: () -> Boolean = { true }): Int {
        var linhas = 0
        destino.parentFile?.mkdirs()
        ZipOutputStream(destino.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(NOME_TEXTO))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            cabecalho.lineSequence().forEach { w.write(mascarar(it)); w.newLine() }
            for (f in arquivos) {
                w.write("===== ${f.name} ====="); w.newLine()
                f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.forEach {
                        if (linhas % 1000 == 0 && !ativo()) {
                            throw java.util.concurrent.CancellationException("geração dos logs cancelada")
                        }
                        w.write(mascarar(it)); w.newLine(); linhas++
                    }
                }
            }
            w.flush()
            zip.closeEntry()
        }
        return linhas
    }
}
