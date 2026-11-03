package net.millida.launcher.core

import net.millida.launcher.core.mojang.loaderMavenFor
import org.junit.Assert.assertEquals
import org.junit.Test

class LoaderMavenHostTest {
    @Test
    fun `библиотеки Forge и NeoForge идут с их мавена, а не с Mojang`() {
        val forgePath = "net/minecraftforge/forge/1.21.11-61.2.1/forge-1.21.11-61.2.1-client.jar"
        assertEquals(
            "https://maven.minecraftforge.net/$forgePath",
            loaderMavenFor("net.minecraftforge:forge:1.21.11-61.2.1:client", "https://libraries.minecraft.net/$forgePath", forgePath),
        )
        val neoPath = "net/neoforged/neoforge/21.1.1/neoforge-21.1.1-universal.jar"
        assertEquals(
            "https://maven.neoforged.net/releases/$neoPath",
            loaderMavenFor("net.neoforged:neoforge:21.1.1:universal", "https://libraries.minecraft.net/$neoPath", neoPath),
        )
    }

    @Test
    fun `явный адрес на другом хосте и чужие библиотеки не трогаются`() {
        val path = "net/minecraftforge/forge/1/forge-1.jar"
        assertEquals("https://cdn.example/x.jar", loaderMavenFor("net.minecraftforge:forge:1", "https://cdn.example/x.jar", path))
        val other = "com/google/guava/guava/1/guava-1.jar"
        assertEquals("https://libraries.minecraft.net/$other", loaderMavenFor("com.google.guava:guava:1", "https://libraries.minecraft.net/$other", other))
    }
}
