package com.sonny.parserag.service.headerfooter;

/**
 * Un bloc = une ligne visuelle (tous les fragments d'une même baseline, à l'intérieur
 * d'une colonne). Coordonnées en points PDF, origine en haut à gauche après ajustement.
 */
record TextBlock(int page,
                 float x0, float y0, float x1, float y1,
                 String text,
                 float pageWidth, float pageHeight) {

    double yTopRatio()    { return pageHeight <= 0 ? 0 : y0 / pageHeight; }
    double yBottomRatio() { return pageHeight <= 0 ? 0 : y1 / pageHeight; }

    /** Le haut du bloc est dans la bande supérieure (header). */
    boolean inHeaderZone(double headerRatio) {
        return yTopRatio() < headerRatio;
    }

    /** Le bas du bloc est dans la bande inférieure (footer). */
    boolean inFooterZone(double footerRatio) {
        return yBottomRatio() > 1.0 - footerRatio;
    }

    boolean inEdgeZone(double headerRatio, double footerRatio) {
        return inHeaderZone(headerRatio) || inFooterZone(footerRatio);
    }
}
