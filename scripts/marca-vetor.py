#!/usr/bin/env python3
"""SVG oficial da marca Ondafone -> VectorDrawable do Android, sem redesenhar nada.

Os SVGs do manual (pasta ondafone-marca/svg) só usam <circle>, <path> com fill/stroke e <g transform="translate()">;
o nome já vem em curvas (sem fonte). O pathData é copiado como está (o Android aceita M/L/H/V/C/Q/Z); o <circle> vira
dois arcos com o mesmo centro e raio. Nada passa por otimizador (svgo), então o desenho é o do arquivo.

  marca-vetor.py <entrada.svg> <saida.xml> --dp LARGURAxALTURA [--caixa N --conteudo M] [--cor #RRGGBB]

--caixa/--conteudo: vetor quadrado de N dp com o viewBox do SVG centralizado em M dp (ícone adaptativo: 108/60).
--cor: troca todas as cores por uma só (ícone monocromático/notificação; o sistema só usa o alfa).
"""
import argparse
import re
import sys
import xml.etree.ElementTree as E

S = "{http://www.w3.org/2000/svg}"
A = "http://schemas.android.com/apk/res/android"
E.register_namespace("android", A)


def a(k):
    return "{%s}%s" % (A, k)


def cor_android(c, forcada):
    if c in (None, "none", "transparent"):
        return None
    c = forcada or c
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", c):
        sys.exit(f"cor não suportada: {c}")
    return "#FF" + c[1:].upper()


def converter(el, pai, forcada):
    for filho in el:
        t = filho.tag.replace(S, "")
        if t == "g":
            g = E.SubElement(pai, "group")
            m = re.fullmatch(r"\s*translate\(\s*([-\d.]+)[\s,]+([-\d.]+)\s*\)\s*", filho.get("transform", "translate(0 0)"))
            if not m:
                sys.exit(f"transform não suportado: {filho.get('transform')}")
            g.set(a("translateX"), m.group(1))
            g.set(a("translateY"), m.group(2))
            converter(filho, g, forcada)
            continue
        if t == "circle":
            cx, cy, r = (float(filho.get(k)) for k in ("cx", "cy", "r"))
            d = f"M{cx - r:g},{cy:g}A{r:g},{r:g} 0 1,0 {cx + r:g},{cy:g}A{r:g},{r:g} 0 1,0 {cx - r:g},{cy:g}Z"
        elif t == "path":
            d = filho.get("d")
        else:
            sys.exit(f"elemento não suportado: <{t}> (o conversor só aceita circle/path/g)")
        p = E.SubElement(pai, "path", {a("pathData"): d})
        fill = cor_android(filho.get("fill", "#000000"), forcada)
        p.set(a("fillColor"), fill or "#00000000")
        stroke = cor_android(filho.get("stroke"), forcada)
        if stroke:
            p.set(a("strokeColor"), stroke)
            p.set(a("strokeWidth"), filho.get("stroke-width", "1"))
            if filho.get("stroke-linecap"):
                p.set(a("strokeLineCap"), filho.get("stroke-linecap"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("entrada")
    ap.add_argument("saida")
    ap.add_argument("--dp", help="LARGURAxALTURA do vetor em dp (padrão: o tamanho do viewBox)")
    ap.add_argument("--caixa", type=float)
    ap.add_argument("--conteudo", type=float)
    ap.add_argument("--cor")
    o = ap.parse_args()

    svg = E.parse(o.entrada).getroot()
    _, _, vw, vh = (float(x) for x in svg.get("viewBox").split())
    v = E.Element("vector")
    if o.caixa:
        s = o.conteudo / max(vw, vh)
        v.attrib.update({a("width"): f"{o.caixa:g}dp", a("height"): f"{o.caixa:g}dp",
                         a("viewportWidth"): f"{o.caixa:g}", a("viewportHeight"): f"{o.caixa:g}"})
        raiz = E.SubElement(v, "group", {a("translateX"): f"{(o.caixa - vw * s) / 2:g}",
                                          a("translateY"): f"{(o.caixa - vh * s) / 2:g}",
                                          a("scaleX"): f"{s:g}", a("scaleY"): f"{s:g}"})
    else:
        w, h = (o.dp or f"{vw:g}x{vh:g}").split("x")
        v.attrib.update({a("width"): f"{w}dp", a("height"): f"{h}dp",
                         a("viewportWidth"): f"{vw:g}", a("viewportHeight"): f"{vh:g}"})
        raiz = v
    converter(svg, raiz, o.cor)
    E.indent(v, "    ")
    nome = o.entrada.rsplit("/", 1)[-1]
    with open(o.saida, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(f"<!-- Marca Ondafone (não é GPL: TRADEMARKS.md). Gerado por scripts/marca-ondafone.sh de {nome}; não editar à mão -->\n")
        f.write(E.tostring(v, encoding="unicode") + "\n")


main()
