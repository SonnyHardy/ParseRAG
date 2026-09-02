/**
 * Mise en place des tests unitaires.
 *
 * jsdom n'implemente pas `ResizeObserver`, dont le `TabList` de PrimeNG se sert dans son
 * `ngAfterViewInit` pour placer la barre d'onglet actif. Sans ce bouchon, tout test montant la
 * page echoue sur `ReferenceError`, pour une raison qui n'a rien a voir avec ce qu'il verifie.
 *
 * Un bouchon inerte suffit : ces tests portent sur le contenu rendu, jamais sur une mesure
 * geometrique. Lui faire simuler des dimensions donnerait une fausse impression de couverture.
 */
class ResizeObserverStub implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;
