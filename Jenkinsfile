#!groovy
pipeline {
    agent any

    environment {
        ENV = "dev"
        PROJECT_ID = "AB-utopia"

        image = null
        packaged = false
        built = false
    }

    stages {
        stage('Package') {
            steps {
                sh "./mvnw package"
            }

            post {
                success {
                    script {
                        packaged = true
                    }
                }
            }
        }

        //TODO
        //stage('SonarQube Analysis') {
        //    steps {
        //        withSonarQubeEnv('SonarQube') {
        //            sh './mvnw org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar'
        //        }
        //    }
        //}

        stage('Build') {
            steps {
                withCredentials([
                    string(
                        credentialsId: "${ENV.toLowerCase()}/${PROJECT_ID}/default",
                        variable: 'SECRETS'
                    )
                ]) {
                    script {
                        def commit = sh(
                            script: "git rev-parse --short=8 HEAD",
                            returnStdout: true
                        ).trim()
                        sh "docker context use default"
                        image = docker.build("$POM_ARTIFACTID -t $commit -t $POM_VERSION springboot-ecr-cd")
                    }
                }
            }

            post {
                success {
                    script {
                        built = true
                    }
                }
            }
        }

        stage('Push to registry') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: "jenkins",
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    script {
                        def region = sh(
                            script:'aws configure get region',
                            returnStdout: true
                        ).trim()
                        def aws_account_id = sh(
                            script:'aws sts get-caller-identity --query "Account" --output text',
                            returnStdout: true
                        ).trim()
                        docker.withRegistry(
                            ecr_uri = "${aws_account_id}.dkr.ecr.${region}.amazonaws.com"
                            "https://$ecr_uri/$POM_ARTIFACTID",
                            "ecr:$region:jenkins"
                        ) {
                            image.push('latest')
                        }
                    }
                }
            }
        }
    }

    post {
        cleanup {
            script {
                if(packaged) {
                    sh "./mvnw clean"

                    if(built) {
                        sh "docker rmi $POM_ARTIFACTID"
                    }
                }
            }
        }
    }
}
