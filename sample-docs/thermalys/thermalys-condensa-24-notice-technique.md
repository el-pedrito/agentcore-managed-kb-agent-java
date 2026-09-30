# Thermalys Condensa 24 : notice technique d'installation et de maintenance

> Document fictif créé pour une démonstration. Toute ressemblance avec un produit existant est fortuite.
> Référence document : TH-C24-NT-FR, révision C, mars 2025.

## 1. Présentation

La Condensa 24 est une chaudière murale gaz à condensation, double service (chauffage et eau chaude sanitaire instantanée).

| Caractéristique | Valeur |
|---|---|
| Puissance utile chauffage (80/60 °C) | 4,8 à 23,6 kW |
| Puissance sanitaire | 28 kW |
| Gaz admis | Gaz naturel G20, G25 ; propane G31 avec kit TH-KP31 |
| Pression de service circuit chauffage | 1,2 à 1,8 bar (à froid) |
| Pression maximale circuit chauffage | 3 bar (soupape de sécurité tarée à 3 bar) |
| Température maximale départ chauffage | 80 °C |
| Débit sanitaire spécifique (ΔT 30 K) | 13,4 l/min |
| Alimentation électrique | 230 V, 50 Hz, 110 W |
| Indice de protection | IPX5D |

## 2. Afficheur et codes défauts

L'afficheur indique un code en cas d'anomalie. Les codes commençant par **F** entraînent une mise en sécurité. Les codes commençant par **A** sont des avertissements : la chaudière continue de fonctionner en mode dégradé.

| Code | Signification | Causes probables | Action technicien |
|---|---|---|---|
| F01 | Défaut d'allumage, absence de flamme après 3 tentatives | Arrivée gaz fermée, électrode d'allumage encrassée ou mal positionnée, pression gaz insuffisante | Vérifier l'ouverture du robinet gaz. Contrôler la pression gaz dynamique (G20 : 20 mbar, tolérance 17 à 25 mbar). Contrôler l'écartement de l'électrode : 4 ± 0,5 mm. Réarmer. |
| F02 | Perte de flamme en fonctionnement | Recirculation des produits de combustion, conduit obstrué, sonde d'ionisation défectueuse | Contrôler l'étanchéité et le dégagement du conduit ventouse. Mesurer le courant d'ionisation : minimum 1,5 µA. |
| F04 | Surchauffe, déclenchement du thermostat de sécurité (105 °C) | Manque de débit d'eau, circulateur bloqué, vannes fermées, air dans le circuit | Ne pas réarmer plus de deux fois. Vérifier le circulateur, purger le circuit, contrôler l'ouverture des vannes. |
| F10 | Sonde de départ NTC1 en court-circuit ou coupée | Sonde défectueuse, connecteur débranché | Mesurer la résistance : 10 kΩ à 25 °C. Remplacer la sonde si hors tolérance. |
| F11 | Sonde sanitaire NTC2 en court-circuit ou coupée | Sonde défectueuse, faisceau endommagé | Mesurer la résistance : 10 kΩ à 25 °C. |
| F20 | Défaut ventilateur, vitesse hors plage | Ventilateur encrassé, câble de commande PWM défectueux | Nettoyer le ventilateur, vérifier le connecteur X7. Remplacer le ventilateur si le défaut persiste. |
| F28 | Pression d'eau du circuit chauffage trop basse (inférieure à 0,5 bar) | Fuite sur l'installation, vase d'expansion dégonflé, soupape qui goutte | Rechercher une fuite. Contrôler le gonflage du vase d'expansion (1 bar à vide). Remettre en pression entre 1,2 et 1,5 bar à froid. |
| F37 | Pression d'eau trop élevée (supérieure à 2,8 bar) | Robinet de remplissage non étanche, vase d'expansion défectueux | Vérifier l'étanchéité du disconnecteur de remplissage, contrôler le vase d'expansion. |
| F62 | Défaut de la vanne gaz | Bobine de la vanne gaz défectueuse, défaut carte | Contrôler la bobine (résistance 1,2 kΩ). Remplacer la vanne gaz. |
| A12 | Entretien annuel à prévoir | Compteur d'heures de service atteint | Réaliser l'entretien annuel puis réinitialiser le compteur (menu Service, paramètre P.33). |
| A28 | Pression d'eau basse (entre 0,5 et 0,8 bar) | Début de fuite, purge récente | Compléter la pression. Surveiller l'évolution. |

## 3. Procédure de réarmement

1. Identifier et noter le code affiché.
2. Supprimer la cause du défaut avant tout réarmement.
3. Appuyer 3 secondes sur la touche RESET.
4. Si le défaut réapparaît plus de deux fois de suite, ne pas insister : mettre la chaudière hors service, informer le client et consigner l'intervention.

## 4. Entretien annuel obligatoire

| Opération | Valeur de référence |
|---|---|
| Nettoyage du corps de chauffe (côté fumées) | Brosse nylon, jamais de brosse métallique |
| Contrôle et nettoyage du siphon des condensats | Remplir le siphon d'eau après nettoyage |
| Contrôle de l'électrode d'allumage | Écartement 4 ± 0,5 mm |
| Analyse de combustion G20 à puissance maximale | CO2 entre 8,8 et 9,4 % |
| Analyse de combustion G20 à puissance minimale | CO2 entre 8,4 et 9,0 % |
| CO non dilué | Inférieur à 150 ppm |
| Contrôle du vase d'expansion | 1 bar à vide |
| Contrôle de la soupape de sécurité | Déclenchement à 3 bar |

Couple de serrage du joint de brûleur : 5 N·m. Remplacer systématiquement le joint de brûleur après démontage.

## 5. Pièces de rechange courantes

| Pièce | Référence |
|---|---|
| Électrode d'allumage et ionisation | TH-PR-1142 |
| Joint de brûleur | TH-PR-0871 |
| Sonde NTC départ | TH-PR-2210 |
| Vanne gaz | TH-PR-3305 |
| Vase d'expansion 8 litres | TH-PR-4018 |
