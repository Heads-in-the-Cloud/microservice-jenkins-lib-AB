def project_id = "AB-utopia"

def image = null
def built = false

def getCommitSha() {
  return sh(
    script: "git rev-parse HEAD",
    returnStdout: true
  ).trim()
}

void setBuildStatus(String message, String state) {
  step([
      $class: "GitHubCommitStatusSetter",
      reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
    commitShaSource: [$class: "ManuallyEnteredShaSource", sha: commitSha],
      errorHandlers: [[$class: "ChangingBuildStatusErrorHandler", result: "UNSTABLE"]],
      statusResultSource: [ $class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]] ]
  ]);
}

def call() {
    pipeline {
        agent any

        stages {
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
                    sh "docker context use default"
                    image_label = "${project_id.toLowerCase()}-$POM_ARTIFACTID"
                    sh "docker tag $image_label -t ${getCommitSha().toSubList(0, 7)}"
                    sh "docker tag $image_label -t $POM_VERSION"
                    script {
                        image = docker.build()
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
                            aws_account_id = sh(
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

                post {
                    cleanup {
                        script {
                            image_label = "${project_id.toLowerCase()}-$POM_ARTIFACTID"
                            sh "docker rmi $image_label:${getCommitSha().toSubList(0, 7)}"
                            sh "docker rmi $image_label:$POM_VERSION"
                            sh "docker rmi $image_label:latest"
                            sh "docker rmi $image_label"
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
