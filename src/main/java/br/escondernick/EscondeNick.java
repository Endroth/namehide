package br.escondernick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class EscondeNick extends JavaPlugin implements Listener, TabExecutor {

    private enum Modo { ESCONDIDO, TAGS }

    private static final String TIME = "en_hide";
    private static final String PERM_USE = "escondernick.use";
    private static final String PERM_TAGS = "escondernick.tags";
    private static final String PERM_ADMIN = "escondernick.admin";

    private final Map<UUID, Modo> modos = new HashMap<>();
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Random random = new Random();

    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask tarefa;

    private int intervalo;
    private int tamanho;
    private double altura;
    private List<Character> chars = new ArrayList<>();
    private TextColor cor = NamedTextColor.WHITE;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        carregarDados();
        carregarConfig();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("escondernick").setExecutor(this);
        getCommand("escondernick").setTabCompleter(this);

        iniciarTarefa();
    }

    @Override
    public void onDisable() {
        if (tarefa != null) tarefa.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            removerDisplay(p);
            removerDoTime(p);
        }
        salvar();
    }

    // ---------------------------------------------------------------- config

    private void carregarConfig() {
        intervalo = Math.max(1, getConfig().getInt("intervalo-ticks", 4));
        tamanho = Math.max(1, getConfig().getInt("tamanho", 6));
        altura = getConfig().getDouble("altura-texto", 0.4);

        chars.clear();
        String c = getConfig().getString("caracteres", "%#@&$!");
        if (c == null || c.isEmpty()) c = "%#@&$!";
        for (char ch : c.toCharArray()) chars.add(ch);

        String corCfg = getConfig().getString("cor", "white");
        TextColor tc = corCfg.startsWith("#")
                ? TextColor.fromHexString(corCfg)
                : NamedTextColor.NAMES.value(corCfg.toLowerCase());
        cor = tc != null ? tc : NamedTextColor.WHITE;
    }

    private void iniciarTarefa() {
        if (tarefa != null) tarefa.cancel();
        tarefa = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, intervalo);
    }

    private Component msg(String chave) {
        String texto = getConfig().getString("mensagens." + chave, chave);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(texto);
    }

    // ---------------------------------------------------------------- dados

    private void carregarDados() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        modos.clear();
        ConfigurationSection sec = data.getConfigurationSection("jogadores");
        if (sec == null) return;
        for (String chave : sec.getKeys(false)) {
            try {
                modos.put(UUID.fromString(chave), Modo.valueOf(sec.getString(chave, "")));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void salvar() {
        data.set("jogadores", null);
        for (Map.Entry<UUID, Modo> e : modos.entrySet()) {
            data.set("jogadores." + e.getKey(), e.getValue().name());
        }
        try {
            getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Nao foi possivel salvar data.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- logica

    private void tick() {
        Set<Scoreboard> boards = boards();
        for (Player p : Bukkit.getOnlinePlayers()) {
            aplicar(p, boards);
        }
    }

    private Set<Scoreboard> boards() {
        Set<Scoreboard> set = new LinkedHashSet<>();
        set.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player p : Bukkit.getOnlinePlayers()) set.add(p.getScoreboard());
        return set;
    }

    private boolean permitido(Player p, Modo m) {
        return m == Modo.TAGS ? p.hasPermission(PERM_TAGS) : p.hasPermission(PERM_USE);
    }

    private void aplicar(Player p, Set<Scoreboard> boards) {
        Modo m = modos.get(p.getUniqueId());
        if (m == null) return;

        // Perdeu a permissao (ex.: tirada no LuckPerms): volta ao normal
        if (!permitido(p, m)) {
            desativar(p);
            salvar();
            p.sendMessage(msg("sem-permissao-removido"));
            return;
        }

        // Esconde o nick de verdade (acima da cabeca) para todo mundo
        for (Scoreboard sb : boards) garantirNoTime(sb, p);

        if (m == Modo.TAGS) atualizarTag(p);
        else removerDisplay(p);
    }

    private void garantirNoTime(Scoreboard sb, Player p) {
        Team t = sb.getTeam(TIME);
        if (t == null) {
            t = sb.registerNewTeam(TIME);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
    }

    private void removerDoTime(Player p) {
        for (Scoreboard sb : boards()) {
            Team t = sb.getTeam(TIME);
            if (t != null && t.hasEntry(p.getName())) t.removeEntry(p.getName());
        }
    }

    private void atualizarTag(Player p) {
        TextDisplay d = displays.get(p.getUniqueId());
        if (d == null || !d.isValid() || !p.getPassengers().contains(d)) {
            if (d != null) d.remove();
            d = criarDisplay(p);
            displays.put(p.getUniqueId(), d);
        }
        d.text(gerarTexto());
    }

    private TextDisplay criarDisplay(Player p) {
        TextDisplay d = p.getWorld().spawn(p.getLocation(), TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(false);
            e.setShadowed(false);
            e.setTransformation(new Transformation(
                    new Vector3f(0f, (float) altura, 0f),
                    new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f),
                    new AxisAngle4f()));
            e.text(gerarTexto());
        });
        p.addPassenger(d);
        p.hideEntity(this, d); // o proprio jogador nao ve o texto
        return d;
    }

    private void removerDisplay(Player p) {
        TextDisplay d = displays.remove(p.getUniqueId());
        if (d != null) d.remove();
    }

    private Component gerarTexto() {
        StringBuilder sb = new StringBuilder();
        if (tamanho == chars.size()) {
            List<Character> copia = new ArrayList<>(chars);
            Collections.shuffle(copia, random);
            for (char c : copia) sb.append(c);
        } else {
            for (int i = 0; i < tamanho; i++) {
                sb.append(chars.get(random.nextInt(chars.size())));
            }
        }
        return Component.text(sb.toString(), cor);
    }

    private void desativar(Player p) {
        modos.remove(p.getUniqueId());
        removerDisplay(p);
        removerDoTime(p);
    }

    private void definirModo(Player p, Modo novo) {
        if (novo == null) {
            desativar(p);
        } else {
            modos.put(p.getUniqueId(), novo);
            aplicar(p, boards());
        }
        salvar();
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        reaplicarDepois(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        reaplicarDepois(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        removerDisplay(p);
        removerDoTime(p);
    }

    private void reaplicarDepois(Player p) {
        if (!modos.containsKey(p.getUniqueId())) return;
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) aplicar(p, boards());
        }, 2L);
    }

    // ---------------------------------------------------------------- comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        if (sub.equals("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                sender.sendMessage(msg("sem-permissao"));
                return true;
            }
            reloadConfig();
            carregarConfig();
            iniciarTarefa();
            sender.sendMessage(msg("reload"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Somente jogadores podem usar esse comando.");
            return true;
        }

        switch (sub) {
            case "" -> {
                if (!p.hasPermission(PERM_USE)) {
                    p.sendMessage(msg("sem-permissao"));
                    return true;
                }
                if (modos.get(p.getUniqueId()) == Modo.ESCONDIDO) {
                    definirModo(p, null);
                    p.sendMessage(msg("etapa1-off"));
                } else {
                    definirModo(p, Modo.ESCONDIDO);
                    p.sendMessage(msg("etapa1-on"));
                }
            }
            case "tags" -> {
                if (!p.hasPermission(PERM_TAGS)) {
                    p.sendMessage(msg("sem-permissao"));
                    return true;
                }
                if (modos.get(p.getUniqueId()) == Modo.TAGS) {
                    definirModo(p, null);
                    p.sendMessage(msg("etapa2-off"));
                } else {
                    definirModo(p, Modo.TAGS);
                    p.sendMessage(msg("etapa2-on"));
                }
            }
            case "off" -> {
                if (!p.hasPermission(PERM_USE) && !p.hasPermission(PERM_TAGS)) {
                    p.sendMessage(msg("sem-permissao"));
                    return true;
                }
                definirModo(p, null);
                p.sendMessage(msg("desativado"));
            }
            default -> p.sendMessage(msg("uso"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String inicio = args[0].toLowerCase();
        if (sender.hasPermission(PERM_TAGS)) out.add("tags");
        if (sender.hasPermission(PERM_USE) || sender.hasPermission(PERM_TAGS)) out.add("off");
        if (sender.hasPermission(PERM_ADMIN)) out.add("reload");
        out.removeIf(s -> !s.startsWith(inicio));
        return out;
    }
}
