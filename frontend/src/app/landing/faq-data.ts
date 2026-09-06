/**
 * Les six questions de la FAQ, source unique (issue #71).
 *
 * Elles vivaient dans le composant `Faq`. Elles en sortent parce que le balisage `FAQPage` doit
 * les refleter mot pour mot : une reponse balisee que la page n'affiche pas est du contenu cache
 * aux yeux d'un moteur, et Google le sanctionne comme tel. Une seule source rend l'ecart
 * impossible plutot qu'improbable.
 *
 * **Les questions sont formulees telles qu'un developpeur les taperait**, et non en intitules de
 * rubrique. C'est ce qui permet a un assistant de reconnaitre la question qu'on lui pose dans
 * celle que la page a deja posee (issue #72).
 *
 * **La premiere phrase de chaque reponse se suffit a elle-meme.** Une reponse qui commence par
 * « Oui, mais seulement si... » n'est citable que collee a sa question ; separee, elle ne veut
 * plus rien dire. Le modele de donnees force la contrainte en separant lead de rest, ce qui la
 * rend visible a la relecture au lieu de dependre de la vigilance de qui ecrit.
 *
 * Le contenu vient de docs/rapidapi-readme.md, section FAQ : la page et le listing repondent la
 * meme chose.
 */
export interface Question {
  readonly value: string;
  readonly question: string;
  /** Premiere phrase, autoportante. Voir la note ci-dessus. */
  readonly lead: string;
  readonly rest?: string;
}

/** La reponse complete, telle que la page l'affiche et que le balisage doit la publier. */
export const answerOf = (q: Question): string => (q.rest ? `${q.lead} ${q.rest}` : q.lead);

export const QUESTIONS: readonly Question[] = [

    {
      value: 'q1',
      question: 'Does it work on scanned documents?',
      lead: 'Yes. Image-only pages skip native extraction and go to a vision model, and those chunks come back with fallback_used set to true.',
      rest: 'Quality depends on the scan, so check the confidence score before indexing.',
    },
    {
      value: 'q2',
      question: 'What languages are supported?',
      lead: 'Text extraction is language-agnostic and works on any language the PDF contains.',
      rest: 'The reported language field detects fr, en, de and es, and returns unknown otherwise. An unknown language does not affect extraction.',
    },
    {
      value: 'q3',
      question: 'How do I extract tables from a PDF as JSON?',
      lead: 'TABLE chunks carry a table_json object with headers, rows and an optional caption, so no separate endpoint or parameter is needed.',
      rest: 'A quality gate decides when a grid Tabula returned badly is worth re-reading with a vision model, which is why a borderless table still comes back structured.',
    },
    {
      value: 'q4',
      question: 'Why is a chunk flagged when the text looks fine?',
      lead: 'The flag is deliberately cautious: it is raised on the signature of a layout problem, not on proof of one.',
      rest: 'A false positive costs you a review; a false negative costs you a poisoned index. The asymmetry is the point.',
    },
    {
      value: 'q5',
      question: 'How long does a parse take?',
      lead: 'A native-text document of 200 pages parses in a few seconds; a scanned document is far slower, because each page goes through a vision model.',
      rest: 'Keep your client timeout high. Vision work runs under a time budget, so a slow provider returns pages flagged for review rather than hanging the request.',
    },
    {
      value: 'q6',
      question: 'Is there a batch endpoint?',
      lead: 'Not today. Send documents one at a time and stay inside your plan’s requests-per-minute limit.',
    },
];
