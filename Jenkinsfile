def commit = "UNKNOWN"
def version = "UNKNOWN"

pipeline {
    agent {
        kubernetes {
            label 'score-executor'
            yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jdk
    tty: true
    image: openjdk:11
    env: 
      - name: DOCKER_HOST 
        value: tcp://localhost:2375 
  - name: dind-daemon 
    image: docker:18.06-dind
    securityContext: 
        privileged: true 
    volumeMounts: 
      - name: docker-graph-storage 
        mountPath: /var/lib/docker 
  - name: helm
    image: alpine/helm:2.12.3
    command:
    - cat
    tty: true
  - name: docker
    image: docker:18-git
    tty: true
    volumeMounts:
    - mountPath: /var/run/docker.sock
      name: docker-sock
  volumes:
  - name: docker-sock
    hostPath:
      path: /var/run/docker.sock
      type: File
  - name: docker-graph-storage 
    emptyDir: {}
"""
        }
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    commit = sh(returnStdout: true, script: 'git describe --always').trim()
                }
                script {
                    version = readMavenPom().getVersion()
                }
            }
        }
        stage('Test') {
            steps {
                container('jdk') {
                    sh "./mvnw test"
                }
            }
        }
        stage('Build & Publish Develop') {
            when {
                branch "develop"
            }
            steps {
                container('docker') {
                    withCredentials([usernamePassword(credentialsId:'OvertureDockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh 'docker login -u $USERNAME -p $PASSWORD'
                    }
                    sh "docker build --target=server --network=host -f Dockerfile . -t overture/score-server:edge -t overture/score-server:${commit}"
                    sh "docker build --target=client --network=host -f Dockerfile . -t overture/score:edge -t overture/score:${commit}"
                    sh "docker push overture/score-server:${commit}"
                    sh "docker push overture/score-server:edge"
                    sh "docker push overture/score:${commit}"
                    sh "docker push overture/score:edge"
                }
            }
        }
        stage('Release & tag') {
          when {
            branch "master"
          }
          steps {
                container('docker') {
                    withCredentials([usernamePassword(credentialsId: 'OvertureBioGithub', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                        sh "git tag ${version}"
                        sh "git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/overture-stack/score --tags"
                    }
                    withCredentials([usernamePassword(credentialsId:'OvertureDockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                        sh 'docker login -u $USERNAME -p $PASSWORD'
                    }
                    sh "docker build --target=server --network=host -f Dockerfile . -t overture/score-server:latest -t overture/score-server:${version}"
                    sh "docker build --target=client --network=host -f Dockerfile . -t overture/score:latest -t overture/score:${version}"
                    sh "docker push overture/score-server:${version}"
                    sh "docker push overture/score-server:latest"
                    sh "docker push overture/score:${version}"
                    sh "docker push overture/score:latest"
                }
            }
        }

        stage('Deploy to Overture QA') {
            when {
                  branch "develop"
            }
            steps {
                container('helm') {
                    withCredentials([file(credentialsId:'4ed1e45c-b552-466b-8f86-729402993e3b', variable: 'KUBECONFIG')]) {
                        sh 'env'
                        sh 'helm init --client-only'
                        sh "helm ls --kubeconfig $KUBECONFIG"
                        sh "helm repo add overture https://overture-stack.github.io/charts-server/"
                        sh """
                            helm upgrade --kubeconfig $KUBECONFIG --install --namespace=overture-qa score-overture-qa \\
                            overture/score --reuse-values --set-string image.tag=${commit}
                           """
                    }
                }
            }
        }

        stage('Deploy to Overture Staging') {
            when {
                  branch "master"
            }
            steps {
                container('helm') {
                    withCredentials([file(credentialsId:'4ed1e45c-b552-466b-8f86-729402993e3b', variable: 'KUBECONFIG')]) {
                        sh 'env'
                        sh 'helm init --client-only'
                        sh "helm ls --kubeconfig $KUBECONFIG"
                        sh "helm repo add overture https://overture-stack.github.io/charts-server/"
                        sh """
                            helm upgrade --kubeconfig $KUBECONFIG --install --namespace=overture-staging score-overture-staging \\
                            overture/score --reuse-values --set-string image.tag=${version}
                           """
                    }
                }
            }
        }

    }
}