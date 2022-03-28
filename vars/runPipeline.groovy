def getCommitSha() {
  return sh(
    script: "git rev-parse HEAD",
    returnStdout: true
  ).trim()
}

// TODO: additional testing needed
// For returning the build status for the associated commit back to GitHub
void setBuildStatus(String message, String state) {
  repoUrl = sh(
    script: "git config --get remote.origin.url",
    returnStdout: true
  ).trim()

  step([
      $class: "GitHubCommitStatusSetter",
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
      commitShaSource: [$class: "ManuallyEnteredShaSource", sha: getCommitSha()],
      errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
}

def call() {
    pipeline {
        agent any

        environment {
            PROJECT_ID = "AB-utopia"

            POM_ARTIFACTID = sh(
                script: './mvnw help:evaluate -Dexpression=project.artifactId -q -DforceStdout',
                returnStdout: true
            )
            POM_VERSION = sh(
                script: './mvnw help:evaluate -Dexpression=project.version -q -DforceStdout',
                returnStdout: true
            )
            SONARQUBE_ID = tool name: 'SonarQubeScanner-4.6.2'

            image = null
            built = false
            image_label = "${PROJECT_ID.toLowerCase()}-$POM_ARTIFACTID"
        }

        stages {

            stage('Maven Package') {
                steps {
                    sh "./mvnw clean package"
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    withCredentials([
                        string(credentialsId: "SonarQube Token", variable: 'SONAR_TOKEN')
                    ]) {
                        sh """
                            ${SONARQUBE_ID}/bin/sonar-scanner \
                                -Dsonar.login=$SONAR_TOKEN \
                                -Dsonar.projectKey=$PROJECT_ID-$POM_ARTIFACTID \
                                -Dsonar.host.url=http://jenkins2.hitwc.link:9000 \
                                -Dsonar.sources=./src/main/java/com/smoothstack/utopia \
                                -Dsonar.java.binaries=./target/classes/com/smoothstack/utopia
                        """
                    }
                }
            }

            stage('Build Docker image') {
                steps {
                    script {
                        sh "docker context use default"
                        image = docker.build(image_label)
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
                            region = sh(
                                script:'aws configure get region',
                                returnStdout: true
                            ).trim()
                            def aws_account_id = sh(
                                script:'aws sts get-caller-identity --query "Account" --output text',
                                returnStdout: true
                            ).trim()
                            def ecr_uri = ${aws_account_id}.dkr.ecr.${region}.amazonaws.com
                            image_url = "https://$ecr_uri/$image_label"
                            sh "aws ecr get-login-password --region $region | docker login --username AWS --password-stdin $ecr_uri"

                            sh "docker tag $image_label $image_url:latest"
                            sh "docker tag $image_label $image_url:$POM_VERSION"
                            sh "docker tag $image_label $image_url:${getCommitSha().substring(0, 7)}"

                            sh "docker push $image_url:latest"
                            sh "docker push $image_url:$POM_VERSION"
                            sh "docker push $image_url:${getCommitSha().substring(0, 7)}"
                        }
                    }
                }

                post {
                    cleanup {
                        script {
                            sh "docker rmi $image_label"
                            sh "docker rmi $image_url:latest"
                            sh "docker rmi $image_url:$POM_VERSION"
                            sh "docker rmi $image_url:${getCommitSha.substring(0, 7)}"
                        }
                    }
                }
            }

            stage("Deploy to EKS") {
                steps {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: "jenkins",
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ]]) {
                        script {
                            sh "aws eks --region $region update-kubeconfig --name $PROJECT_ID"
                            sh "kubectl -n microservices set image deployments/$POM_ARTIFACTID $POM_ARTIFACTID=$image_url:latest"
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    if(built) {
                        setBuildStatus("Build complete", "SUCCESS")
                    } else {
                        setBuildStatus("Build failed", "FAILURE")
                    }
                }
            }
        }
    }
}
