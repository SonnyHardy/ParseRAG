import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { SITE_NAME, SOCIAL_IMAGE, absolute, type SitePage } from './site';
import { homeGraph, pageGraph } from './structured-data';

/**
 * Pose les metadonnees d'une page : titre, description, canonique, Open Graph, Twitter, JSON-LD
 * (issue #71).
 *
 * **Tout est pose pendant le prerendu**, donc present dans le HTML servi sans qu'une ligne de
 * JavaScript s'execute. C'est la seule chose qui compte ici : un moteur qui recoit une page sans
 * balise `canonical` ne va pas attendre qu'un script la lui donne. Les tags sont ecrits une fois
 * par le rendu serveur, et la meme methode s'execute a l'hydratation puis a chaque changement de
 * route.
 *
 * **D'ou la regle qui structure ce fichier : on met a jour, on n'ajoute jamais.** Le `<head>` n'est
 * pas hydrate par Angular ; les balises du prerendu y sont deja quand le navigateur reprend la
 * main. Un `appendChild` naif produirait deux `canonical` et deux blocs JSON-LD, ce qu'un moteur
 * lit comme une contradiction plutot que comme une repetition. Chaque element est donc retrouve
 * par un selecteur stable et modifie sur place.
 */
@Injectable({ providedIn: 'root' })
export class Seo {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly document = inject(DOCUMENT);

  /** Applique les metadonnees d'une page du site. `home` ajoute le produit et la FAQ au graphe. */
  apply(page: SitePage, kind: 'home' | 'page' = 'page'): void {
    const url = absolute(page.path);
    const image = absolute(SOCIAL_IMAGE.path);

    this.title.setTitle(page.title);

    // `name=` pour la description, `property=` pour Open Graph : ce ne sont pas deux ecritures du
    // meme attribut, et les mélanger fait ignorer la balise par l'un ou l'autre des consommateurs.
    this.setName('description', page.description);

    this.setProperty('og:type', kind === 'home' ? 'website' : 'article');
    this.setProperty('og:site_name', SITE_NAME);
    this.setProperty('og:locale', 'en_US');
    this.setProperty('og:title', page.title);
    this.setProperty('og:description', page.description);
    this.setProperty('og:url', url);
    this.setProperty('og:image', image);
    this.setProperty('og:image:width', String(SOCIAL_IMAGE.width));
    this.setProperty('og:image:height', String(SOCIAL_IMAGE.height));
    this.setProperty('og:image:alt', SOCIAL_IMAGE.alt);

    // `summary_large_image` et non `summary` : c'est ce qui donne l'apercu en 1200x630 plutot
    // qu'une vignette carree, seule raison d'avoir dessine cette image.
    this.setName('twitter:card', 'summary_large_image');
    this.setName('twitter:title', page.title);
    this.setName('twitter:description', page.description);
    this.setName('twitter:image', image);
    this.setName('twitter:image:alt', SOCIAL_IMAGE.alt);

    this.setCanonical(url);
    this.setJsonLd(kind === 'home' ? homeGraph(page.description) : pageGraph(page));
  }

  private setName(name: string, content: string): void {
    this.meta.updateTag({ name, content });
  }

  private setProperty(property: string, content: string): void {
    this.meta.updateTag({ property, content }, `property='${property}'`);
  }

  private setCanonical(url: string): void {
    const head = this.document.head;
    let link = head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'canonical');
      head.appendChild(link);
    }
    link.setAttribute('href', url);
  }

  /**
   * Le bloc JSON-LD, marque par un identifiant pour etre retrouve plutot que duplique.
   *
   * `textContent` et non `innerHTML` : le contenu est du JSON produit par nos soins, mais passer
   * par `innerHTML` rendrait une future donnee d'origine externe injectable, et la difference ne
   * se verrait a la relecture qu'une fois le degat fait.
   */
  private setJsonLd(graph: unknown): void {
    const head = this.document.head;
    let script = head.querySelector<HTMLScriptElement>('script#pr-jsonld');
    if (!script) {
      script = this.document.createElement('script');
      script.id = 'pr-jsonld';
      script.type = 'application/ld+json';
      head.appendChild(script);
    }
    script.textContent = JSON.stringify(graph);
  }
}
