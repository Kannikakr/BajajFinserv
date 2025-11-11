# bfh-qualifier1
Spring Boot app for Bajaj Finserv Health | Qualifier 1 (JAVA)

## How it works
- On startup, calls generateWebhook API:
  POST https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA
  with name, regNo, email.
- Receives `webhook` and `accessToken`.
- Builds final SQL query based on last two digits of regNo:
  - odd -> Question 1 query
  - even -> Question 2 query
- Sends POST to webhook URL with JSON `{ "finalQuery": "SQL HERE" }` and `Authorization` header set to the returned accessToken.

## How to run
1. Edit `src/main/java/com/example/bfh/BfhAppApplication.java` and set `name`, `regNo`, and `email`.
2. Build:
