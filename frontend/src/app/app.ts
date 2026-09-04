import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Coquille de l'application.
 *
 * Elle ne contient qu'un router-outlet depuis que le site porte, en plus de la landing page,
 * les deux pages legales liees en pied de page. La page elle-meme vit dans landing/landing-page.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: '<router-outlet />',
  styleUrl: './app.scss',
})
export class App {}
