import { Routes } from '@angular/router';
import { LandingPage } from './landing/landing-page';
import { pageFor } from './seo/site';

/**
 * Trois routes, toutes statiques et toutes prerendues (app.routes.server.ts).
 *
 * Les deux pages legales sont chargees a la demande : elles ne sont lues que par une minorite de
 * visiteurs et n'ont aucune raison de peser sur le bundle de la page d'accueil.
 *
 * Les titres viennent de `seo/site.ts` depuis l'issue #71, la ou vivent aussi la description, la
 * canonique et le sitemap. Recopies ici, le titre de l'onglet et celui de la balise `og:title`
 * auraient fini par differer, et personne ne l'aurait vu.
 */
export const routes: Routes = [
  { path: '', component: LandingPage, title: pageFor('/').title },
  {
    path: 'terms',
    loadComponent: () => import('./legal/terms').then((m) => m.Terms),
    title: pageFor('/terms').title,
  },
  {
    path: 'privacy',
    loadComponent: () => import('./legal/privacy').then((m) => m.Privacy),
    title: pageFor('/privacy').title,
  },
];
