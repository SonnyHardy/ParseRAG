/**
 * L'URL du listing, definie une seule fois.
 *
 * Elle est la seule conversion de la page et apparait sur cinq CTA. Dupliquee, elle divergerait
 * le jour ou l'issue #74 y ajoutera des parametres UTM par section, et une moitie des liens
 * cesserait d'etre attribuee sans que rien ne le signale.
 */
export const RAPIDAPI_URL = 'https://rapidapi.com/parserag-parserag-default/api/parserag';
