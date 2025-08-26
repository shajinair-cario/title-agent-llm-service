# Doc Extract Service

A Spring Boot 3 Microservice that processes documents from AWS S3 using **AWS TEXTRACT**, evaluates extraction confidence, saves output back to S3, and tracks file status in **DynamoDB**.  

It also provides REST APIs with **Swagger UI** for easy testing.



## Prerequisites

### Install JDK 17

#### macOS (Homebrew)
```bash
brew install openjdk@17
brew link --force --overwrite openjdk@17
```

#### Linux (Debian/Ubuntu)
```bash
sudo apt update
sudo apt install openjdk-17-jdk -y
```

#### Windows

- Download from [Adoptium](https://adoptium.net/temurin/releases/?version=17). <br><br>
- Install and set `JAVA_HOME` in Environment Variables

#### Verify Installation

```bash
java -version
# Expected:
# openjdk version "17.0.x"
```

---
# Install Maven

#### macOS (Homebrew)
```bash
brew install maven
```

#### Linux
```bash
sudo apt install maven -y
```

#### Windows

- Download from [Apache Maven](https://maven.apache.org/download.cgi). <br><br>
- Extract and set `MAVEN_HOME`, then update `PATH`

#### Verify Installation
```bash
mvn -v
# Should display Maven version and Java home
```

---

## Project Configuration

### AWS Resource creations

- Ensure the AWS credential and profiles files (aws-config, aws-credentials) are in the root directory. Update them with your crdentials. <br><br>

- Run the script aws-resource-build.sh to create S3,DynamoDB resources. <br><br>

- Run the script aws-resource-destroy.sh to remove the resources.


### AWS Configuration (Local Profile)
Edit `src/main/resources/application-local.yml`:

```yaml
spring:
  config:
    activate:
      on-profile: local

aws:
  region: us-east-1
  accessKeyId: <YOUR_ACCESS_KEY>
  secretAccessKey: <YOUR_SECRET_KEY>
  s3:
    output-bucket: your-output-bucket
    output-prefix: textract-output/

dynamodb:
  tableName: file_status

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always

logging:
  level:
    root: INFO
```


---

## Build and run with local profile

### Format code and build

- From the project root directory run the following commands <br><br>
 
- format the code: mvn spotless:apply <br><br>
 
- install the code as a spring boot jar - mvn clean install



### Run with Local Profile

- Run mvn spring-boot:run -Dspring-boot.run.profiles=local <br><br>

- Service URL: http://localhost:8080


### Build the docker image

- From the root directory run the script docket-build.sh


### Run the with local docker 

- compose directory compose file to run locally using docker locally
- follow the docker compose specs to containerize the service via CI/CD pipeline
- on release one, use the local profile (akas all properties are from spring profile property (src/main/resources/application-local.yml), same under src/test/resources for local mvn clean run with full app build out.


### Publish to AWS ECR repository

- Run the script docker-publish-to-aws-ecr.sh


## API documentation on local host 


- Swagger UI: 
  
  http://localhost:8080/swagger-ui/index.html <br><br>
  
- OpenAPI JSON:  
  
  http://localhost:8080/v3/api-docs
  
- OpenAPI YAML
  http://localhost:8080/v3/api-docs.yaml
  
  
  

### Example end point


- GET /api/textract/analyze?bucket=my-bucket&key=myfile.pdf


Parameters:
- `bucket` — S3 bucket name
- `key` — Path/key of the file in S3

---

## Health Check

 
- GET http://localhost:8080/actuator/health

<br><br>

## Notes
- Use the `production` profile for deployment (`application-production.yml`). <br><br>
- Ensure AWS IAM permissions include:
  - `s3:GetObject`
  - `s3:PutObject`
  - `dynamodb:PutItem`
  - `dynamodb:UpdateItem`
