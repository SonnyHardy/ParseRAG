/**
 * Les codes d'erreur de l'API, source unique (issue #72).
 *
 * Ils viennent de `docs/rapidapi-readme.md`, section « Errors » : la page, le listing et
 * `llms-full.txt` repondent la meme chose. Le backend les produit depuis `ParseRagException`, avec
 * une forme de reponse stable : `{"error": "<CODE>", "message": "...", "status": <int>}`.
 *
 * **Pourquoi un tableau de faits sur une page vitrine.** L'issue le demande, et la raison n'est pas
 * cosmetique : un `<table>` semantique est la structure la plus fiablement extraite par un agent,
 * loin devant une liste stylee. Un developpeur qui evalue une API veut savoir comment elle echoue
 * avant de savoir comment elle reussit, et un assistant a qui l'on demande « que renvoie ParseRAG
 * si le PDF fait 80 Mo » doit pouvoir citer `FILE_TOO_LARGE` et `413` sans les inventer.
 *
 * `code` est stable entre les versions et c'est sur lui qu'un client branche ; `message` est ecrit
 * pour un humain et peut changer. Cette distinction est publiee avec le tableau, sans quoi
 * quelqu'un finira par comparer des chaines de message.
 */
export interface ApiError {
  readonly code: string;
  readonly status: number;
  readonly when: string;
  /** Reessayer a-t-il une chance d'aboutir ? Formule pour etre lue telle quelle. */
  readonly retry: string;
}

export const API_ERRORS: readonly ApiError[] = [
  {
    code: 'MISSING_FILE',
    status: 400,
    when: 'No file part, or an empty one.',
    retry: 'No, fix the request',
  },
  {
    code: 'INVALID_FILE_FORMAT',
    status: 415,
    when: 'Content type is neither application/pdf nor application/octet-stream.',
    retry: 'No',
  },
  {
    code: 'INVALID_FILE_FORMAT',
    status: 400,
    when: 'Content type was right, but the bytes do not start with the %PDF signature.',
    retry: 'No',
  },
  {
    code: 'UNSUPPORTED_MEDIA_TYPE',
    status: 415,
    when: 'The request itself is not multipart/form-data.',
    retry: 'No',
  },
  {
    code: 'FILE_TOO_LARGE',
    status: 413,
    when: 'Over the 50 MB limit.',
    retry: 'No',
  },
  {
    code: 'PDF_UNREADABLE',
    status: 400,
    when: 'Corrupted, or encrypted with a password.',
    retry: 'No',
  },
  {
    code: 'DOCUMENT_TOO_LONG',
    status: 422,
    when: 'More pages than your plan allows.',
    retry: 'No, split or upgrade',
  },
  {
    code: 'RATE_LIMIT_EXCEEDED',
    status: 429,
    when: 'You are sending too fast. Wait the number of seconds in Retry-After.',
    retry: 'Yes, after the delay',
  },
  {
    code: 'SERVICE_BUSY',
    status: 503,
    when: 'The service is at capacity. Parses are bounded to protect memory.',
    retry: 'Yes, shortly',
  },
  {
    code: 'DATABASE_UNAVAILABLE',
    status: 503,
    when: 'A dependency is temporarily down.',
    retry: 'Yes, shortly',
  },
];
