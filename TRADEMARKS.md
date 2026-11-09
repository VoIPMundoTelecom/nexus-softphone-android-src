# Marcas (Trademarks)

O código deste aplicativo é software livre sob a **GNU GPL versão 3** (veja `LICENSE.txt` e `NOTICE.md`).
A licença cobre o **código**, não as marcas.

**Ondafone** e o logotipo Ondafone, assim como **Voipmundo**, **i9c** e **Nexus** (nome anterior do app), os
respectivos nomes, logotipos, ícones e identidade visual, são marcas da **IVM Telecomunicação (CNPJ
10.285.037/0001-67)** e **não são licenciados pela GPL**. Conforme a seção 7(e) da GPLv3, recusamos a concessão de
direitos de uso desses nomes e marcas.

Por isso, quem distribuir uma versão modificada (fork) deste código **deve trocar a marca**: nome do app
(`appName`), ícones, logotipos, cores e textos que mencionem Ondafone, Nexus, Voipmundo ou i9c, e o identificador
do pacote (`applicationId` / `br.com.grupoivm.nexus`) — e não dar a entender que o fork é um produto da IVM
Telecomunicação nem que é suportado por ela.

**Recomendação (não é condição da licença do código):** trocar também o endpoint de provisionamento. O app aceita
configuração só do givm (`api.givm.com.br` / `app.givm.com.br`, `/api/prov/<token>`), que é um **serviço** da IVM
Telecomunicação para os próprios clientes; o uso desse serviço segue os termos do serviço, não a GPL. Um fork deve apontar para
o próprio servidor de provisionamento.

Linphone e Belledonne Communications são marcas dos respectivos titulares; o uso aqui é só para os créditos
exigidos pela licença.

---

*Trademarks.* The code is free software under the GNU GPLv3. Ondafone and the Ondafone logo, as well as Voipmundo,
i9c and Nexus (the app's former name), are trademarks of IVM Telecomunicação (CNPJ 10.285.037/0001-67) and are
**not** licensed under the GPL (GPLv3 section 7(e)). Forks must replace the branding and must not imply endorsement
by IVM Telecomunicação. Replacing the provisioning endpoint (an IVM Telecomunicação service governed by its own
terms, not by the GPL) is recommended.
