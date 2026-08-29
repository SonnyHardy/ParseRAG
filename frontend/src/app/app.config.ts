import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideClientHydration } from '@angular/platform-browser';
import { providePrimeNG } from 'primeng/config';
import Aura from '@primeuix/themes/aura';

import { routes } from './app.routes';
import { PRIMENG_LICENSE_KEY } from '../generated/primeng-license';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideClientHydration(),
    providePrimeNG({
      // Cle generee au build depuis PRIMENG_LICENSE_KEY (scripts/write-license.mjs). PrimeNG 22
      // n'est plus MIT et l'attend meme sous licence Community.
      license: PRIMENG_LICENSE_KEY,
      theme: {
        // Aura est le preset de depart. Les couleurs de marque le surchargeront par tokens a
        // l'issue #68, quand le canevas Claude Design les aura produits : surcharger un preset
        // coute moins cher que d'en ecrire un, et garde les mises a jour de PrimeNG applicables.
        preset: Aura,
        options: {
          // La landing page porte son propre theme sombre par `prefers-color-scheme` (issue #68).
          // `darkModeSelector` pointe donc sur une classe que nous controlons, plutot que sur le
          // `system` par defaut : sans cela, PrimeNG et nos propres tokens pourraient basculer sur
          // deux signaux differents et se contredire a l'ecran.
          darkModeSelector: '.parserag-dark',
        },
      },
      // Ripple desactive : c'est une animation d'application, sans objet sur une page vitrine, et
      // elle ajoute un rendu au premier clic sur le seul element qui compte, le CTA.
      ripple: false,
    }),
  ],
};
