def getCommitSha() {
  return sh(
    script: "git rev-parse HEAD",
    returnStdout: true
  ).trim()
}

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
            project_id = "AB-utopia"

            POM_ARTIFACTID = sh(
                script: 'mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout',
                returnStdout: true
            )
            POM_VERSION = sh(
                script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout',
                returnStdout: true
            )

            image = null
            built = false
        }

        stages {

            stage('Package') {
                withMaven {
                    sh "mvn clean package"
                }
            }

            stage('SonarQube Analysis') {
                steps {
                    sh 'mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar'
                }
            }

            stage('Build') {
                steps {
                    script {
                        sh "docker context use default"
                        def image_label = "${project_id.toLowerCase()}-$POM_ARTIFACTID"
                        image = docker.build(image_label)
                        sh "docker tag $image_label -t ${getCommitSha().toSubList(0, 7)}"
                        sh "docker tag $image_label -t $POM_VERSION"
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

                post {
                    cleanup {
                        script {
                            def image_label = "${project_id.toLowerCase()}-$POM_ARTIFACTID"
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
