# LLM-Based Retrieval-Augmented Generation (RAG) System
A Retrieval-Augmented Generation (RAG) application designed to improve the factual grounding and reliability of Large Language Model (LLM) responses by retrieving relevant
document evidence and using it to generate context-aware answers.

## Overview
Large Language Models can generate plausible responses even when the required information is unavailable in their training data or the supplied context.
This project explores a document-grounded approach in which answers are generated using retrieved evidence rather than relying solely on the model's parametric knowledge.
The system integrates document ingestion, text chunking, embedding-based retrieval, evidence assessment, and LLM-based response generation. It also incorporates mechanisms for handling missing information and validating citation references.
The primary objective is to investigate how retrieval and evidence verification can help improve the factual reliability of LLM-powered question-answering systems.

## Key Features
 **Document Ingestion:** Upload and process documents for knowledge retrieval.
 **Text Chunking:** Divide documents into smaller passages suitable for retrieval.
 **Semantic Retrieval:** Retrieve relevant information using embedding-based similarity.
 **Hybrid Retrieval:** Combine semantic and lexical retrieval approaches.
 **Context-Aware Generation:** Generate responses using retrieved document evidence.
 **Evidence Assessment:** Evaluate whether retrieved passages support a proposed answer.
 **Missing Information Handling:** Identify questions for which the available evidence does not specify the requested information.
 **Citation Validation:** Validate citation references associated with generated responses.
 **Document-Based Question Answering:** Enable users to interact with their uploaded documents through natural-language queries.

## System Architecture

The application follows a frontend-backend architecture.

                 USER
                   |
                   v
          Angular Frontend
                   |
              REST APIs
                   |
                   v
          Spring Boot Backend
                   |
          Document Processing
                   |
             Text Chunking
                   |
             Embeddings
                   |
                   v
          Retrieval Pipeline
          /              \
   Semantic Search   Lexical Search
          \              /
           Hybrid Retrieval
                   |
                   v
           Evidence Assessment
                   |
                   v
           Ollama LLM Inference
                   |
                   v
          Citation Validation
                   |
                   v
           Grounded Response

*Note: This is a conceptual representation of the processing workflow.*

## Technology Stack

| Component | Technology |
 Backend --> Java, Spring Boot 
 Frontend --> Angular 
 Database --> PostgreSQL 
 LLM Runtime --> Ollama 
 Language Model --> Qwen 2.5 3B Instruct 
 Embedding Model --> nomic-embed-text 
 API Architecture --> REST APIs 
 Build Tool ---> Maven 
 Retrieval ---> Semantic and lexical retrieval 

## RAG Processing Workflow

### 1. Document Ingestion
Documents are uploaded and processed by the backend to prepare their contents for retrieval.

### 2. Text Chunking
Document contents are divided into smaller text segments to support more focused retrieval.

### 3. Embedding Generation
Text segments are transformed into vector representations using an embedding model.

### 4. Hybrid Retrieval
The retrieval pipeline uses semantic and lexical signals to identify relevant passages for a user query.

### 5. Evidence Assessment
Retrieved passages are examined to determine whether they provide sufficient evidence for the requested answer.

### 6. LLM-Based Answer Generation
The retrieved context and user question are supplied to the language model to generate a context-aware response.

### 7. Citation Validation
Citation references are checked to identify missing or invalid references in the generated response.

### 8. Missing Information Handling
When the retrieved evidence does not specify the requested information, the system is designed to avoid unsupported answers and indicate that the information
is not specified.

## Research Motivation

This project is motivated by an important challenge in Retrieval-Augmented Generation: retrieving relevant documents does not necessarily guarantee that the
generated answer is factually supported by those documents.

The implementation explores several related aspects:

- Evidence-grounded response generation.
- Handling insufficient or missing contextual information.
- Evaluating the relationship between retrieved passages and generated claims.
- Improving citation reference reliability.
- Reducing unsupported responses in document-based question answering.

These aspects are relevant to research in Natural Language Processing, Information Retrieval, and trustworthy LLM applications.

## Getting Started

### Prerequisites

Ensure the following tools are installed:

- Java (compatible with the project's Maven configuration)
- Maven
- PostgreSQL
- Ollama
- Node.js and Angular CLI (for the frontend)

### 1. Clone the Repository

git clone https://github.com/rdgupta93/llm-rag-backend.git

cd llm-rag-backend


### 2. Configure the Database

Create a PostgreSQL database and configure the database connection in your local application configuration.

Do not commit database passwords, API keys, JWT secrets, or other sensitive configuration values to the repository.

### 3. Configure Ollama

Install and start Ollama.

Pull the required language and embedding models:


ollama pull qwen2.5:3b-instruct

ollama pull nomic-embed-text


Ensure Ollama is running and accessible to the backend.

### 4. Run the Backend

Using the Maven wrapper:

**Windows**

mvnw.cmd spring-boot:run

**Linux/macOS**

./mvnw spring-boot:run


The backend will start using the configuration defined in the application.

## Repository Structure

llm-rag-backend/
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── llmrag/
│       │           └── llm_rag_backend/
│       │               ├── controller/
│       │               ├── service/
│       │               └── ...
│       └── resources/
├── .gitignore
├── pom.xml
├── mvnw
└── mvnw.cmd

*The structure above illustrates the main project organization; additional packages and files may exist.*

## Research Limitations

The current implementation is an exploratory engineering and research project.

Its effectiveness should be evaluated through broader and more systematic experiments, including:

- Larger and more diverse document collections.
- Multiple question-answering datasets.
- Retrieval precision and recall.
- Evidence-to-claim entailment evaluation.
- Citation correctness and completeness.
- Robustness to ambiguous and insufficient evidence.
- Comparative evaluation across language models and retrieval configurations.

Citation-label validation alone does not establish that a cited passage semantically supports every generated claim. 
Further evaluation is required to measure factual correctness and evidence entailment.

## Future Work

Potential directions for further development include:

- Benchmark-based evaluation of RAG pipelines.
- Improved evidence-to-claim verification.
- More robust citation attribution.
- Retrieval optimization and reranking.
- Evaluation across different LLM architectures.
- Domain-specific applications in financial services and enterprise knowledge management.

## Author

**Ramdayal Gupta**

M.Sc. Computer Science  
University of Mumbai

Research Interests:
- Natural Language Processing
- Retrieval-Augmented Generation
- Large Language Models
- Information Retrieval
- Trustworthy and Explainable AI
- AI Applications in Financial Services

## Related Research

**Improving Factual Grounding and Citation Reliability in Retrieval-Augmented Generation Systems**

Preprint: https://zenodo.org/records/24112981

DOI: https://doi.org/10.5281/zenodo.24112981

## Repository

GitHub: https://github.com/rdgupta93/llm-rag-backend

---

*This project is intended for research and educational purposes. The implementation and its evaluation are exploratory and should not be interpreted as a validated production-grade factual verification system.*
