# Copilot Instructions for FTP Design and Implementation

## Overview
This project implements an FTP server using socket programming. The architecture is designed to handle multiple client sessions concurrently, ensuring efficient data transfer and management.

## Architecture
- **Major Components**:
  - `FtpServer.java`: The main server class that initializes and manages client connections.
  - `ClientSession.java`: Handles individual client sessions, processing commands and data transfers.
  - `DataConnection.java`: Manages data connections for file transfers.
  - `UserStore.java`: Manages user authentication and storage.
  - `PathValidator.java`: Validates file paths for security and correctness.

- **Data Flows**:
  - Clients connect to the server, which spawns a new `ClientSession` for each connection.
  - Commands are processed in the session, invoking methods in `DataConnection` for file transfers.

## Developer Workflows
- **Building the Project**:
  - Use `javac` to compile the Java files in the `src/` directory:
    ```bash
    javac src/*.java -d bin/
    ```

- **Running the Server**:
  - Execute the server from the `bin/` directory:
    ```bash
    java -cp bin FtpServer
    ```

- **Testing**:
  - Ensure to run tests after making changes to validate functionality. Use JUnit for unit tests if applicable.

## Project-Specific Conventions
- **File Naming**: Classes are named using CamelCase, and files should be organized in the `src/` directory.
- **Error Handling**: Use exceptions to handle errors gracefully, providing meaningful messages to clients.

## Integration Points
- **External Dependencies**: Ensure that any external libraries (if used) are included in the classpath during compilation and execution.
- **Cross-Component Communication**: Components communicate through method calls and shared data structures. Ensure thread safety when accessing shared resources.

## Examples
- **Starting the Server**:
  ```bash
  java -cp bin FtpServer
  ```
- **Validating Paths**:
  Use `PathValidator` to check paths before processing file commands.

## Conclusion
This document serves as a guide for AI coding agents to understand the architecture, workflows, and conventions of the FTP project. For further details, refer to the source code in the `src/` directory and the `README.md` for project-specific instructions.