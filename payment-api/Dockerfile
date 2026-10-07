# =============================================================================
# Build multi-etapes : l'image finale ne contient ni le JDK, ni Maven, ni les sources.
#
# L'enjeu est la surface d'attaque. Une image avec le JDK complet et un shell donne a
# un attaquant de quoi compiler et explorer ; une image minimale ne lui offre presque
# rien. Sur un composant du CDE, chaque paquet en moins est une CVE en moins a suivre
# et a justifier (PCI DSS req. 6).
# =============================================================================

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Les dependances sont resolues dans une couche separee : elles ne changent qu'avec le
# pom.xml, donc le cache Docker evite de les retelecharger a chaque modification de code.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# -----------------------------------------------------------------------------
# Image d'execution : JRE seul, distribution minimale.
FROM eclipse-temurin:17-jre-alpine AS runtime

# curl est requis par le health check de la task definition ECS. On installe le strict
# necessaire et on nettoie le cache dans la meme couche.
RUN apk add --no-cache curl=~8 || apk add --no-cache curl

# Utilisateur non privilegie : un conteneur qui tourne en root donne l'equivalent du
# root de l'hote en cas d'evasion. Exigence explicite de durcissement.
RUN addgroup -S payment && adduser -S payment -G payment

WORKDIR /app
COPY --from=build --chown=payment:payment /build/target/*.jar app.jar

USER payment

EXPOSE 8080

# MaxRAMPercentage plutot que -Xmx fixe : la JVM s'adapte a la memoire reellement
# allouee a la tache Fargate. Un -Xmx code en dur devient faux des qu'on change la
# taille de la tache, et le conteneur se fait tuer par l'OOM killer.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError est delibere : une JVM qui survit a un OOM dans un etat
# incoherent est pire qu'une tache qui meurt et qu'ECS remplace proprement.

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
