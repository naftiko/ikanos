#!/bin/sh 
cd "$(dirname "$0")/.." || exit 1

# FUNCTIONS
print(){ printf "%s" "$*"; }
println(){ printf "%s\n" "$*"; }

build(){
  mvn clean package --no-transfer-progress 
}

deploy_local(){
  mvn clean install -DskipTests=true --no-transfer-progress
}

deploy_github(){
  println "Deploying to GitHub Packages..."
  mvn clean deploy -DskipTests=true --no-transfer-progress
}

deploy_central(){
  println "Deploying to Maven Central..."
  mvn clean deploy \
    -P central \
    -DskipTests=true \
    -Dgpg.passphrase=$GPG_PASSPHRASE \
    -Dgpg.pinentry.mode=loopback \
    --no-transfer-progress
}

deploy_remote(){
  deploy_central
}

config_gpg(){
  if [ "$GPG_SIGNING_KEY" = "" ]; then
    println "ERROR: No GPG_SIGNING_KEY defined"
    exit 200
  fi

  mkdir -p ~/.gnupg/
  chmod 700 ~/.gnupg/
  
  echo "${GPG_SIGNING_KEY}" > ~/.gnupg/private.key
  chmod 600 ~/.gnupg/private.key
  
  if ! gpg --batch --import ~/.gnupg/private.key 2>/dev/null; then
    println "ERROR: Failed to import GPG key. Ensure it's in ASCII armor format."
    println "Expected format: -----BEGIN PGP PRIVATE KEY BLOCK-----"
    exit 201
  fi
  
  cat <<EOF > ~/.gnupg/gpg.conf
use-agent
pinentry-mode loopback
EOF

  cat <<EOF > ~/.gnupg/gpg-agent.conf
allow-loopback-pinentry
EOF

  gpgconf --kill gpg-agent || true
  gpg-agent --daemon --allow-loopback-pinentry || true
  
  println "✓ GPG configured"
}

config_maven_github(){
  if [ "$GITHUB_TOKEN" = "" ]; then
    println "ERROR: Variable GITHUB_TOKEN not defined"
    exit 202
  fi

  mkdir -p ~/.m2
  
  cat <<EOF> ~/.m2/settings.xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>\${env.GITHUB_ACTOR}</username>
      <password>\${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
EOF
  
  println "✓ Maven configured for GitHub Packages"
}

config_maven_central(){
  if [ "$CENTRAL_USERNAME" = "" ] || [ "$CENTRAL_PASSWORD" = "" ]; then
    println "ERROR: Variables CENTRAL_USERNAME or CENTRAL_PASSWORD not defined"
    println "Get your credentials on: https://central.sonatype.com/account"
    exit 201
  fi

  mkdir -p ~/.m2
  
  cat <<EOF> ~/.m2/settings.xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>\${env.CENTRAL_USERNAME}</username>
      <password>\${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
EOF
  
  println "✓ Maven configured for Maven Central"
}

config_maven_all(){
  if [ "$GITHUB_TOKEN" = "" ]; then
    println "WARNING: GITHUB_TOKEN not defined, skipping GitHub config"
  fi
  
  if [ "$CENTRAL_USERNAME" = "" ] || [ "$CENTRAL_PASSWORD" = "" ]; then
    println "WARNING: CENTRAL_USERNAME or CENTRAL_PASSWORD not defined, skipping Central config"
  fi

  mkdir -p ~/.m2
  
  cat <<EOF> ~/.m2/settings.xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>\${env.GITHUB_ACTOR}</username>
      <password>\${env.GITHUB_TOKEN}</password>
    </server>
    <server>
      <id>central</id>
      <username>\${env.CENTRAL_USERNAME}</username>
      <password>\${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
EOF
  
  println "✓ Maven configured for GitHub Packages AND Maven Central"
}

# MAIN
case "$1" in
  "build") build ;;
  "deploy_local"|"local") deploy_local ;;
  "deploy_github"|"github") deploy_github ;;
  "deploy_central"|"central") deploy_central ;;
  "deploy"|"remote") deploy_remote ;;
  "config_maven_github") config_maven_github ;;
  "config_maven_central"|"config_maven") config_maven_central ;;
  "config_maven_all") config_maven_all ;;
  "config_gpg") config_gpg ;;
  *)
    cat <<EOF | sed 's/^[ \t]*//'
      Usage: $0 <OPTION>

      Where OPTION is one of the following:
      
      Build:
      - build                    
      - local / deploy_local     
      
      Deploy:
      - github / deploy_github   
      - central / deploy_central 
      - deploy / remote          
      
      Configuration:
      - config_maven_github      
      - config_maven_central     
      - config_maven / config_maven_all 
      - config_gpg               

      Examples:
      - Publish snapshot to GitHub: $0 github
      - Publish release to Maven Central: $0 central

EOF
    exit 1
  ;;
esac