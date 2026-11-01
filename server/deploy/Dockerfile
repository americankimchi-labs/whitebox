# The mirror server on Cloud Run. Built from the server's installed distribution (server/deploy/deploy.sh builds it
# first with Gradle), so the image needs no build tools or Android SDK.
FROM eclipse-temurin:17-jre
COPY build/install/server /app
ENV PORT=8080
CMD ["/app/bin/server"]
