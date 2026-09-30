# Vaporis Ecoline 35 : manuel de service

> Document fictif créé pour une démonstration. Toute ressemblance avec un produit existant est fortuite.
> Référence document : VP-EL35-MS-FR, édition 2024-11.

## 1. Présentation

L'Ecoline 35 est une chaudière gaz à condensation au sol, chauffage seul, destinée aux petits collectifs et locaux tertiaires. Elle peut être installée en cascade jusqu'à 4 appareils.

| Caractéristique | Valeur |
|---|---|
| Puissance utile (50/30 °C) | 7,5 à 36,2 kW |
| Gaz admis | Gaz naturel G20, G25 |
| Pression de service | 1,5 à 2,5 bar |
| Pression maximale de service | 4 bar |
| Température maximale départ | 85 °C |
| Régulation | Sonde extérieure et module cascade VP-CAS4 |

## 2. Codes défauts

Attention : la codification Vaporis est différente de celle des autres fabricants. Un même numéro de code n'a pas la même signification d'une marque à l'autre.

| Code | Signification | Causes probables | Action technicien |
|---|---|---|---|
| F01 | Défaut de flamme au démarrage | Air dans la conduite gaz après intervention, électrode défectueuse | Purger la conduite gaz. Contrôler l'électrode (écartement 3,5 mm). |
| F09 | Écart de température départ et retour trop important (supérieur à 35 K) | Débit insuffisant, filtre retour colmaté, circulateur sous-dimensionné | Nettoyer le filtre du retour, contrôler la vitesse du circulateur. |
| F18 | Défaut de communication avec le module cascade | Bus eBUS coupé, adresse cascade en double | Contrôler le câblage eBUS (polarité non significative). Vérifier les adresses des appareils (paramètre C.02). |
| F28 | Défaut d'allumage répété, verrouillage après 5 tentatives | Pression gaz insuffisante, vanne gaz défectueuse, siphon des condensats bouché provoquant un refoulement | Contrôler la pression gaz dynamique (G20 : 20 mbar). Nettoyer le siphon des condensats. Contrôler la vanne gaz. Le réarmement se fait uniquement après élimination de la cause. |
| F33 | Pressostat air : absence de tirage | Conduit de fumées obstrué, ventilateur défectueux, tuyau du pressostat débranché | Contrôler le conduit et le tuyau silicone du pressostat. |
| F75 | Pression d'eau trop basse, capteur de pression | Manque d'eau (inférieur à 0,8 bar), capteur défectueux | Remettre en pression à 1,8 bar. Si la pression affichée diffère du manomètre, remplacer le capteur (réf. VP-SP-0450). |
| F83 | Absence de montée en température après allumage | Circuit chauffage vide ou fortement aéré | Purger l'installation et le corps de chauffe. |
| E96 | Tension d'alimentation insuffisante (inférieure à 190 V) | Réseau électrique instable | Contrôler l'alimentation. La chaudière redémarre automatiquement au retour d'une tension correcte. |

## 3. Réarmement

Le réarmement s'effectue par la touche de déverrouillage située sous le cache frontal. Après 3 réarmements en moins de 15 minutes, l'appareil se verrouille et impose une remise sous tension.

## 4. Entretien

- Nettoyage de l'échangeur en inox : produit détartrant non acide uniquement (réf. VP-NET-01).
- Analyse de combustion G20 : CO2 entre 9,0 et 9,6 % à pleine charge.
- Contrôle du neutraliseur de condensats en installation collective : remplacer les granulés tous les 2 ans.
- Contrôle du serrage des connexions électriques de la carte principale.

## 5. Pièces de rechange

| Pièce | Référence |
|---|---|
| Électrode d'allumage | VP-SP-0112 |
| Capteur de pression d'eau | VP-SP-0450 |
| Vanne gaz | VP-SP-0620 |
| Pressostat air | VP-SP-0733 |
