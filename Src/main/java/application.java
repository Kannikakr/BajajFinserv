package com.example.bfh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Map;

@SpringBootApplication
public class BfhAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(BfhAppApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ApplicationRunner run(RestTemplate restTemplate) {
        return args -> {
            ObjectMapper mapper = new ObjectMapper();

            // ======= 1) Replace these values with yours ========
            String name = "John Doe";
            String regNo = "REG12347";   // IMPORTANT: set your regNo here
            String email = "john@example.com";
            // ==================================================

            String genUrl = "https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA";

            Map<String, String> genBody = Map.of(
                    "name", name,
                    "regNo", regNo,
                    "email", email
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> genRequest = new HttpEntity<>(genBody, headers);

            System.out.println("Calling generateWebhook...");
            ResponseEntity<String> genResponse = restTemplate.postForEntity(genUrl, genRequest, String.class);

            if (!genResponse.getStatusCode().is2xxSuccessful()) {
                System.err.println("generateWebhook failed: " + genResponse.getStatusCode() + " body: " + genResponse.getBody());
                return;
            }

            JsonNode genJson = mapper.readTree(genResponse.getBody());
            String webhookUrl = genJson.path("webhook").asText(null);
            String accessToken = genJson.path("accessToken").asText(null);

            if (webhookUrl == null || accessToken == null) {
                System.err.println("Missing webhook or accessToken. Response: " + genResponse.getBody());
                return;
            }

            System.out.println("Received webhook: " + webhookUrl);
            System.out.println("Received accessToken: (hidden)");

            // ===== Decide question based on last two digits of regNo =====
            // Extract last two digits (non-digit chars ignored).
            String digits = regNo.replaceAll("\\D+", "");
            int lastTwo = 0;
            if (digits.length() >= 2) {
                lastTwo = Integer.parseInt(digits.substring(digits.length() - 2));
            } else if (digits.length() == 1) {
                lastTwo = Integer.parseInt(digits);
            } else {
                System.out.println("No digits in regNo; defaulting to odd (Question 1)");
                lastTwo = 1;
            }

            boolean isEven = (lastTwo % 2 == 0);
            String finalSql;
            if (!isEven) {
                // Question 1 -> highest salary not on 1st day
                finalSql = """
                    SELECT
                      pmx.SALARY,
                      CONCAT(e.FIRST_NAME, ' ', e.LAST_NAME) AS NAME,
                      TIMESTAMPDIFF(YEAR, e.DOB, CURDATE()) AS AGE,
                      d.DEPARTMENT_NAME
                    FROM
                      (SELECT MAX(AMOUNT) AS SALARY
                       FROM PAYMENTS
                       WHERE DAY(PAYMENT_TIME) <> 1) pmx
                    JOIN PAYMENTS p ON p.AMOUNT = pmx.SALARY
                      AND DAY(p.PAYMENT_TIME) <> 1
                    JOIN EMPLOYEE e ON p.EMP_ID = e.EMP_ID
                    JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID;
                    """;
            } else {
                // Question 2 -> count of younger employees in same dept for each employee
                finalSql = """
                    SELECT
                      e.EMP_ID,
                      e.FIRST_NAME,
                      e.LAST_NAME,
                      d.DEPARTMENT_NAME,
                      COALESCE((
                        SELECT COUNT(1)
                        FROM EMPLOYEE e2
                        WHERE e2.DEPARTMENT = e.DEPARTMENT
                          AND TIMESTAMPDIFF(YEAR, e2.DOB, CURDATE()) < TIMESTAMPDIFF(YEAR, e.DOB, CURDATE())
                      ), 0) AS YOUNGER_EMPLOYEES_COUNT
                    FROM EMPLOYEE e
                    JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID
                    ORDER BY e.EMP_ID DESC;
                    """;
            }

            System.out.println("Final SQL prepared (first 200 chars): ");
            System.out.println(finalSql.length() > 200 ? finalSql.substring(0, 200) + "..." : finalSql);

            // ======= 3) Post final SQL to webhook URL with Authorization header ========
            HttpHeaders postHeaders = new HttpHeaders();
            postHeaders.setContentType(MediaType.APPLICATION_JSON);

            // ==== IMPORTANT: Some platforms expect "Bearer <token>", others expect raw token.
            // Put either "Bearer " + accessToken or accessToken only.
            // If one fails, try the other.
            postHeaders.set("Authorization", accessToken); // <--- try this first (raw token)
            // postHeaders.set("Authorization", "Bearer " + accessToken); // <-- or uncomment to use Bearer

            String payload = mapper.writeValueAsString(Map.of("finalQuery", finalSql));
            HttpEntity<String> finalRequest = new HttpEntity<>(payload, postHeaders);

            System.out.println("Submitting final query to webhook...");
            ResponseEntity<String> submitResponse;
            try {
                submitResponse = restTemplate.postForEntity(webhookUrl, finalRequest, String.class);
            } catch (Exception ex) {
                System.err.println("Submission failed (attempting fallback to testWebhook): " + ex.getMessage());
                // Fallback: call the documented testWebhook endpoint if webhookUrl fails
                String fallback = "https://bfhldevapigw.healthrx.co.in/hiring/testWebhook/JAVA";
                submitResponse = restTemplate.postForEntity(fallback, finalRequest, String.class);
            }

            System.out.println("Submission status: " + submitResponse.getStatusCode());
            System.out.println("Submission body: " + submitResponse.getBody());
            System.out.println("Done.");
        };
    }
}


// SELECT
//   pmx.SALARY,
//   CONCAT(e.FIRST_NAME, ' ', e.LAST_NAME) AS NAME,
//   TIMESTAMPDIFF(YEAR, e.DOB, CURDATE()) AS AGE,
//   d.DEPARTMENT_NAME
// FROM
//   (SELECT MAX(AMOUNT) AS SALARY
//    FROM PAYMENTS
//    WHERE DAY(PAYMENT_TIME) <> 1) pmx
// JOIN PAYMENTS p ON p.AMOUNT = pmx.SALARY
//   AND DAY(p.PAYMENT_TIME) <> 1
// JOIN EMPLOYEE e ON p.EMP_ID = e.EMP_ID
// JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID;


// SELECT
//   e.EMP_ID,
//   e.FIRST_NAME,
//   e.LAST_NAME,
//   d.DEPARTMENT_NAME,
//   COALESCE((
//     SELECT COUNT(1)
//     FROM EMPLOYEE e2
//     WHERE e2.DEPARTMENT = e.DEPARTMENT
//       AND TIMESTAMPDIFF(YEAR, e2.DOB, CURDATE()) < TIMESTAMPDIFF(YEAR, e.DOB, CURDATE())
//   ), 0) AS YOUNGER_EMPLOYEES_COUNT
// FROM EMPLOYEE e
// JOIN DEPARTMENT d ON e.DEPARTMENT = d.DEPARTMENT_ID
// ORDER BY e.EMP_ID DESC;
