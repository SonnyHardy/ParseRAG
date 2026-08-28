# ParseRAG — image de production (issue #58).
#
# Multi-etages : le JDK et le depot Maven restent dans l'etage de build, l'image finale ne porte
# qu'un JRE et le jar. Sans cette separation, on expedierait un compilateur et quelques centaines
# de Mo de dependances de build en production.

# ── Etage 1 : build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Le wrapper et le pom d'abord, seuls : tant qu'aucune dependance ne change, cette couche est
# reutilisee et le telechargement du depot Maven n'est pas rejoue a chaque modification de source.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests exclus de l'image : ils tournent en CI, ou une base Postgres est disponible. Les rejouer
# ici sans base ferait echouer le build sur ParseRagApplicationTests.contextLoads.
RUN ./mvnw -B -q clean package -DskipTests

# ── Etage 2 : execution ───────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime

# fontconfig et une police de base sont indispensables : PDFBox rend les pages en images pour le
# fallback vision et l'extraction de tableaux. Sans elles, le rendu part en exception ou produit
# des pages vides - une panne qui ne se voit qu'en production, sur les documents scannes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core curl \
    && rm -rf /var/lib/apt/lists/*

# Utilisateur non privilegie : un parse manipule des fichiers fournis par l'exterieur, il n'a
# aucune raison de tourner en root.
RUN useradd --system --uid 10001 --create-home parserag
USER parserag
WORKDIR /app

COPY --from=build --chown=parserag:parserag /build/target/*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage : par defaut la JVM prend une fraction du conteneur sans rapport avec notre
#   profil memoire (un upload de 50 Mo charge entier, un rendu de page a ~9 Mo).
# ExitOnOutOfMemoryError : mieux vaut mourir et etre redemarre que boiter en avalant des requetes.
# headless : aucune interface graphique, mais AWT est utilise pour le rendu PDF.
# UseContainerSupport est actif par defaut ; on ne le repete pas.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
ENV SPRING_PROFILES_ACTIVE=prod

# Sonde de vivacite : /actuator/health rend un UP/DOWN nu, sans detail et sans authentification
# (cf. application.yaml). L'endpoint metier /api/v1/health, lui, reste reserve a l'administrateur.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
