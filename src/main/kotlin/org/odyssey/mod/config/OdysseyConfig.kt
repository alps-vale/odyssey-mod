package org.odyssey.mod.config

import net.fabricmc.loader.api.FabricLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.odyssey.mod.OdysseyDiagnostics
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path

@Serializable
internal data class OdysseyConfig(
    val autoConnect: Boolean = true,
    val bridgeVisible: Boolean = true,
    val discordRankOverrides: Boolean = true,
) {
    companion object {
        private val json = Json { prettyPrint = true }
        private val path by lazy {
            FabricLoader.getInstance().configDir.resolve("odyssey.json")
        }

        fun load(): OdysseyConfig = load(path)

        internal fun load(path: Path): OdysseyConfig {
            if (!Files.exists(path)) {
                return OdysseyConfig().also { save(it, path) }
            }

            return try {
                json.decodeFromString<OdysseyConfig>(Files.readString(path))
            } catch (error: Throwable) {
                OdysseyDiagnostics.logger.warn(
                    "[Odyssey Mod] Failed to load config {}; replacing it with defaults",
                    path,
                    error,
                )
                OdysseyConfig().also { save(it, path) }
            }
        }

        fun save(config: OdysseyConfig) {
            save(config, path)
        }

        private fun save(config: OdysseyConfig, path: Path) {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(temporary, json.encodeToString(serializer(), config))
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
