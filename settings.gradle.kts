val name: String by settings

rootProject.name = name

val localApi = File("../MiniGamesAPI")

if (localApi.exists() && localApi.isDirectory) {
    includeBuild(localApi) {
        dependencySubstitution {
            substitute(module("ru.joutak:minigamesapi"))
                .using(project(":"))
        }
    }
    println("Using local MiniGamesAPI from: ${localApi.absolutePath}")
} else {
    println("Local MiniGamesAPI not found, using Maven repository dependency")
}
