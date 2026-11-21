# Bookstore Backend – IBM Applied AI Specialist Capstone

A RESTful eCommerce bookstore backend built using Java, Spring Boot and PostgreSQL. Developed with an agentic AI-assisted workflow using IBM Bob.

## Technology Stack

- Java 21
- Spring Boot 3.5.16
- Maven
- PostgreSQL
- Spring Data JPA / Hibernate
- Spring Security and JWT authentication
- BCrypt password hashing
- Flyway database migrations
- OpenAPI 3.0.3
- JUnit and Spring Boot Test

## Features

- Customer registration, login and JWT authentication
- Role-based access control for customers and administrators
- Book catalogue with categories, brands and product details
- Product search and related products
- Shopping cart management
- Customer address management
- Order creation and order history
- Simulated credit/debit card payment processing
- Order cancellation within the eligible 48-hour window
- Gift points earning and redemption
- Personalized product recommendations

## Project Structure

```text
bookstore-capstone/
├── src/
│   ├── main/
│   │   ├── java/com/capstone/bookstore/
│   │   └── resources/
│   │       └── db/migration/
│   └── test/
├── openapi.yaml
├── pom.xml
├── .gitignore
└── README.md
```

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL

## Configuration

Configure the following environment variables for your local PostgreSQL database:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/bookstore"
$env:DB_USERNAME = "postgres"
$env:SERVER_PORT = "8080"
```

Configure the database password and JWT secret securely using your local environment. Do not commit credentials or secrets.

## Run the Application

```powershell
mvn clean verify
mvn spring-boot:run
```

The API context path is:

`http://localhost:8080/api/v1`

Flyway applies the database migrations during application startup.

## API Documentation

The OpenAPI specification is available in `openapi.yaml`.

## Automated Testing

Run the complete verification suite:

```powershell
mvn clean verify
```

Latest verification result:

- Tests run: 436
- Failures: 0
- Errors: 0
- Skipped: 0
- Build: SUCCESS

## Payment Simulation

This project uses simulated card payment processing for demonstration and testing. It does not process real financial transactions. Only the last four card digits are retained in payment records.

## AI-Assisted Development

IBM Bob was used as an agentic development assistant to support planning, implementation, code review, debugging and test development. Generated code and changes were reviewed and validated through automated tests and API-level verification.

## Disclaimer

This project was developed as an educational capstone exercise. It is not intended for production financial transactions without additional security, compliance and operational reviews.
