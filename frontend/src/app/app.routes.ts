import { Routes } from '@angular/router';
import { LandingPage } from './landing/landing-page';

/**
 * Trois routes, toutes statiques et toutes prerendues (app.routes.server.ts).
 *
 * Les deux pages legales sont chargees a la demande : elles ne sont lues que par une minorite de
 * visiteurs et n'ont aucune raison de peser sur le bundle de la page d'accueil.
 */
export const routes: Routes = [
  { path: '', component: LandingPage, title: 'ParseRAG: PDF Parsing API for RAG' },
  {
    path: 'terms',
    loadComponent: () => import('./legal/terms').then((m) => m.Terms),
    title: 'Terms of Use | ParseRAG',
  },
  {
    path: 'privacy',
    loadComponent: () => import('./legal/privacy').then((m) => m.Privacy),
    title: 'Privacy and data protection | ParseRAG',
  },
];
