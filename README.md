## 🧪 Используемые технологии

- **Kotlin + Spring Boot** — Бекенд
- **Gradle (Kotlin)** — сборка проекта
- **WebSocket** — потоковая передача данных
- **Docker + Docker Compose** — контейнеризация и деплой

---

## 🛠️ Установка и запуск (локально)

Требования:
> JRE >= 21

> Docker

Клонируй репозиторий:
   ```bash
   git clone https://github.com/sonso-team/hackathon-spring-2025-api.git
   cd hackathon-spring-2025-api
```
Билд и запуск проекта:
Windows:
```bash
gradle clean build
docker compose up --build -d
```
Linux:
```bash
sudo chmod +x ./gradlew
./gradlew clean build
cd /build/libs
docker compose up --build -d
```

# или
https://hack.uwu-devcrew.ru/
