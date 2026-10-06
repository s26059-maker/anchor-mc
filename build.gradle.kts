plugins {
    java
}

group = "dev.anchormc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven(url = "https://repo.codemc.io/repository/maven-releases/") { name = "codemc" }
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

    // PacketEvents: 1.2단계에서 이 프로젝트에 허용한 유일한 외부 라이브러리. 최신 안정판 2.14.0(2026-09-23, 공식 문서·릴리스 페이지로 확인).
    // 서버에는 PacketEvents 플러그인을 따로 설치한다(plugin.yml depend). 그래서 jar에 넣지 않는다(compileOnly).
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")

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

// sim 소스셋은 jar에 안 들어가서 test·build가 컴파일하지 않았다(문법 오류가 실행 때까지 안 잡혔다). test가 먼저 컴파일하게 해 build(check)에서도 잡힌다.
tasks.test {
    dependsOn(tasks.named("compileSimJava"))
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

tasks.register<JavaExec>("runBench") {
    group = "verification"
    description = "청크 패킷 수정의 CPU·추가 바이트를 실제 PacketEvents 청크 객체로 잰다."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.anchormc.plugin.PacketBench")
    maxHeapSize = "2g"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
