/**
 * Les trois exemples d'appel, colores au jeton comme dans le design.
 *
 * Un jeton porte une classe courte plutot qu'une couleur : k mot-cle, f fonction ou commande,
 * s chaine, p ponctuation, t texte courant. Les couleurs sont resolues en CSS a partir des
 * tokens du theme, donc changer la palette ne demande pas de toucher a ce fichier.
 *
 * code porte la meme chose en texte brut. Ce n'est pas une duplication decorative : c'est ce que
 * le bouton Copier met dans le presse-papiers, et reconstituer le texte en concatenant les jetons
 * a chaque clic reintroduirait les espaces de mise en page dans ce qu'on colle.
 */
export type TokenClass = 'k' | 'f' | 's' | 'p' | 't';

export type Token = readonly [TokenClass, string];

export interface Snippet {
  readonly id: 'curl' | 'python' | 'java';
  readonly label: string;
  readonly lines: readonly (readonly Token[])[];
  readonly code: string;
}

const CURL: Snippet = {
  id: 'curl',
  label: 'cURL',
  lines: [
    [
      ['f', 'curl'],
      ['t', ' -X POST \\'],
    ],
    [
      ['t', '  '],
      ['s', 'https://parserag.p.rapidapi.com/api/v1/parse'],
      ['t', ' \\'],
    ],
    [
      ['t', '  -H '],
      ['s', '"X-RapidAPI-Key: YOUR_KEY"'],
      ['t', ' \\'],
    ],
    [
      ['t', '  -F '],
      ['s', '"file=@document.pdf"'],
    ],
  ],
  code: `curl -X POST \\
  https://parserag.p.rapidapi.com/api/v1/parse \\
  -H "X-RapidAPI-Key: YOUR_KEY" \\
  -F "file=@document.pdf"`,
};

const PYTHON: Snippet = {
  id: 'python',
  label: 'Python',
  lines: [
    [
      ['k', 'import'],
      ['t', ' requests'],
    ],
    [['t', ' ']],
    [
      ['k', 'with '],
      ['f', 'open'],
      ['p', '('],
      ['s', '"document.pdf"'],
      ['p', ', '],
      ['s', '"rb"'],
      ['p', ') '],
      ['k', 'as '],
      ['t', 'f'],
      ['p', ':'],
    ],
    [
      ['t', '    r '],
      ['p', '= '],
      ['t', 'requests'],
      ['p', '.'],
      ['f', 'post'],
      ['p', '('],
    ],
    [
      ['p', '        '],
      ['s', '"https://parserag.p.rapidapi.com/api/v1/parse"'],
      ['p', ','],
    ],
    [
      ['p', '        '],
      ['t', 'headers'],
      ['p', '={'],
      ['s', '"X-RapidAPI-Key"'],
      ['p', ': '],
      ['s', '"YOUR_KEY"'],
      ['p', '},'],
    ],
    [
      ['p', '        '],
      ['t', 'files'],
      ['p', '={'],
      ['s', '"file"'],
      ['p', ': '],
      ['t', 'f'],
      ['p', '},'],
    ],
    [['p', '    )']],
    [['t', ' ']],
    [
      ['t', 'chunks '],
      ['p', '= '],
      ['t', 'r'],
      ['p', '.'],
      ['f', 'json'],
      ['p', '()['],
      ['s', '"chunks"'],
      ['p', ']'],
    ],
  ],
  code: `import requests

with open("document.pdf", "rb") as f:
    r = requests.post(
        "https://parserag.p.rapidapi.com/api/v1/parse",
        headers={"X-RapidAPI-Key": "YOUR_KEY"},
        files={"file": f},
    )

chunks = r.json()["chunks"]`,
};

const JAVA: Snippet = {
  id: 'java',
  label: 'Java',
  lines: [
    [
      ['f', 'RequestBody '],
      ['t', 'body '],
      ['p', '= '],
      ['k', 'new '],
      ['f', 'MultipartBody.Builder'],
      ['p', '()'],
    ],
    [
      ['p', '    .'],
      ['f', 'setType'],
      ['p', '('],
      ['t', 'MultipartBody.FORM'],
      ['p', ')'],
    ],
    [
      ['p', '    .'],
      ['f', 'addFormDataPart'],
      ['p', '('],
      ['s', '"file"'],
      ['p', ', '],
      ['s', '"document.pdf"'],
      ['p', ','],
    ],
    [
      ['p', '        '],
      ['f', 'RequestBody'],
      ['p', '.'],
      ['f', 'create'],
      ['p', '('],
      ['k', 'new '],
      ['f', 'File'],
      ['p', '('],
      ['s', '"document.pdf"'],
      ['p', '),'],
    ],
    [
      ['p', '            '],
      ['f', 'MediaType'],
      ['p', '.'],
      ['f', 'parse'],
      ['p', '('],
      ['s', '"application/pdf"'],
      ['p', ')))'],
    ],
    [
      ['p', '    .'],
      ['f', 'build'],
      ['p', '();'],
    ],
    [['t', ' ']],
    [
      ['f', 'Request '],
      ['t', 'request '],
      ['p', '= '],
      ['k', 'new '],
      ['f', 'Request.Builder'],
      ['p', '()'],
    ],
    [
      ['p', '    .'],
      ['f', 'url'],
      ['p', '('],
      ['s', '"https://parserag.p.rapidapi.com/api/v1/parse"'],
      ['p', ')'],
    ],
    [
      ['p', '    .'],
      ['f', 'post'],
      ['p', '('],
      ['t', 'body'],
      ['p', ')'],
    ],
    [
      ['p', '    .'],
      ['f', 'addHeader'],
      ['p', '('],
      ['s', '"X-RapidAPI-Key"'],
      ['p', ', '],
      ['s', '"YOUR_KEY"'],
      ['p', ')'],
    ],
    [
      ['p', '    .'],
      ['f', 'build'],
      ['p', '();'],
    ],
  ],
  code: `RequestBody body = new MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", "document.pdf",
        RequestBody.create(new File("document.pdf"),
            MediaType.parse("application/pdf")))
    .build();

Request request = new Request.Builder()
    .url("https://parserag.p.rapidapi.com/api/v1/parse")
    .post(body)
    .addHeader("X-RapidAPI-Key", "YOUR_KEY")
    .build();`,
};

export const SNIPPETS: readonly Snippet[] = [CURL, PYTHON, JAVA];

/**
 * La reponse affichee a droite, coloree de la meme facon.
 *
 * C'est la reponse **complete** de l'API et non un extrait : document_id, language,
 * processing_ms et les champs de chunk que le design abregeait. Un developpeur qui evalue une
 * API vient chercher la forme exacte de ce qu'il recevra, et un agent qui lit la page ne peut
 * citer que ce qui s'y trouve (issue #72). Le bloc defile dans son propre conteneur, donc sa
 * longueur ne pousse pas la mise en page.
 */
export const RESPONSE_LINES: readonly (readonly Token[])[] = [
  [['p', '{']],
  [
    ['p', '  '],
    ['t', '"document_id"'],
    ['p', ': '],
    ['s', '"doc_9f3c1a7b"'],
    ['p', ','],
  ],
  [
    ['p', '  '],
    ['t', '"pages"'],
    ['p', ': '],
    ['t', '12'],
    ['p', ','],
  ],
  [
    ['p', '  '],
    ['t', '"language"'],
    ['p', ': '],
    ['s', '"en"'],
    ['p', ','],
  ],
  [
    ['p', '  '],
    ['t', '"processing_ms"'],
    ['p', ': '],
    ['t', '1843'],
    ['p', ','],
  ],
  [
    ['p', '  '],
    ['t', '"status"'],
    ['p', ': '],
    ['s', '"ok"'],
    ['p', ','],
  ],
  [
    ['p', '  '],
    ['t', '"chunks"'],
    ['p', ': ['],
  ],
  [['p', '    {']],
  [
    ['p', '      '],
    ['t', '"id"'],
    ['p', ': '],
    ['s', '"chunk_doc_9f3c1a7b_000"'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['t', '"text"'],
    ['p', ': '],
    ['s', '"Retrieval-augmented generation grounds a language model in an external corpus..."'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['t', '"type"'],
    ['p', ': '],
    ['s', '"PARAGRAPH"'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['t', '"page"'],
    ['p', ': '],
    ['t', '1'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['k', '"confidence"'],
    ['p', ': '],
    ['k', '0.94'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['t', '"fallback_used"'],
    ['p', ': '],
    ['t', 'false'],
    ['p', ','],
  ],
  [
    ['p', '      '],
    ['t', '"manual_review_needed"'],
    ['p', ': '],
    ['t', 'false'],
  ],
  [['p', '    }']],
  [['p', '  ]']],
  [['p', '}']],
];
