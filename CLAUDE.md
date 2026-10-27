# Commerce Lab — instructions for Claude Code

This is a learning lab, not a product. The owner is building engineering depth by hand.

## Always
- Read `CARDS.md` first. Work only on the card in **Now** unless told otherwise.
- Stack: Java 25, Spring Boot 4.x, Spring Data JPA, PostgreSQL, Flyway, Gradle (Kotlin DSL), JUnit 5, Testcontainers.

## Do NOT
- Write implementation code or tests for card work unless I explicitly say "generate". I write the hard parts myself.
- Add dependencies, modules or infrastructure (Kafka, Redis, security, etc.) that the current card doesn't need.
- Edit the "Prediction" section of any lab note.

## Do
- Generate boilerplate when I ask (entities for seeded tables, config, migrations, DTOs).
- Review and attack my code: race conditions, transaction boundaries, failure cases, missing tests. Point out problems; let me fix them.
- Answer "why" questions with explanations, not rewritten code.
- When I say "close session": update the Parking note in `CARDS.md`, move finished cards to Done with the date, and nothing else.
