/**
 * La grille des plans, source unique (issue #71).
 *
 * Elle vivait dans le composant `Plans`. Elle en sort parce que le balisage `SoftwareApplication`
 * doit la refleter : deux copies divergeraient au premier ajustement de grille, et un balisage qui
 * annonce un plan que la page ne montre plus est pire qu'un balisage absent.
 *
 * Origine : `docs/rapidapi-listing-setup.md`, section « Grille proposee ». Les limites de pages
 * sont celles que le backend applique reellement (`parserag.page-limits`) ; les deux doivent bouger
 * ensemble, un chiffre affiche plus haut que celui qui est applique se payant en
 * `DOCUMENT_TOO_LONG` inattendu.
 *
 * Les noms sont ceux du marketplace (BASIC/PRO/ULTRA/MEGA), jamais les noms internes
 * (FREE/STARTER/PRO/SCALE). Le faux ami est reel : `PRO` cote RapidAPI vaut `STARTER` chez nous.
 */
export interface Plan {
  readonly name: string;
  readonly perMonth: string;
  readonly perMinute: string;
  readonly pages: string;
  /** Part de la limite de pages du plan le plus haut, pour la barre de la section. */
  readonly share: number;
  readonly free?: boolean;
  /**
   * Prix mensuel en dollars, **jamais affiche sur la page**.
   *
   * Le brief de design l'interdit et pour une bonne raison : un tarif recopie ici se perimerait au
   * premier ajustement sans que rien ne le signale. Il n'existe que pour le bloc `offers` du
   * JSON-LD, ou l'issue #71 le demande explicitement, et il est publie sur le listing RapidAPI —
   * c'est-a-dire a l'adresse que chaque `Offer` porte en `url`. Un test verrouille l'invariant :
   * aucun montant ne doit apparaitre dans le texte de la page.
   */
  readonly monthlyUsd: string;
}

export const PLANS: readonly Plan[] = [
  {
    name: 'BASIC',
    perMonth: '50',
    perMinute: '2',
    pages: '100',
    share: 10,
    free: true,
    monthlyUsd: '0',
  },
  { name: 'PRO', perMonth: '1,000', perMinute: '5', pages: '300', share: 30, monthlyUsd: '9.99' },
  {
    name: 'ULTRA',
    perMonth: '7,500',
    perMinute: '10',
    pages: '500',
    share: 50,
    monthlyUsd: '49.99',
  },
  {
    name: 'MEGA',
    perMonth: '50,000',
    perMinute: '20',
    pages: '1,000',
    share: 100,
    monthlyUsd: '199.99',
  },
];
