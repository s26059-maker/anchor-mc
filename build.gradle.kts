plugins {
    java
}

group = "dev.anchormc"
version = "0.1.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

// 시뮬레이터는 플러그인 jar에 들어가지 않도록 별도 소스셋으로 둔다. core 패키지만 쓴다(Bukkit 의존 없음).
sourceSets {
    create("sim") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    // 최신 안정판(2026-09-30 확인: 26.2.build.129-stable). 이 버전의 클래스 파일이 Java 25(class 69)다.
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // 서버에서는 plugin.yml의 libraries로 Paper가 내려받는다. 테스트에서만 직접 필요.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging { events("failed"); showStandardStreams = false }
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.register<JavaExec>("runSim") {
    group = "verification"
    description = "서버 없이 채굴 에이전트 시뮬레이터를 돌린다. 예: gradlew runSim --args=\"--runs 100\""
    classpath = sourceSets["sim"].runtimeClasspath
    mainClass.set("dev.anchormc.sim.Simulation")
    maxHeapSize = "2g"
}
