package fr.troll.headdisguise;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HeadDisguise extends JavaPlugin implements Listener {

    private NamespacedKey markerKey;
    private NamespacedKey recipeKey;
    private final Map<UUID, PlayerProfile> originals = new HashMap<>();

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "transform_head");
        recipeKey = new NamespacedKey(this, "transform_head_recipe");

        // Recette : tete de joueur au centre, 8 eclats d'amethyste autour.
        // Le resultat reel (avec le skin) est construit dans PrepareItemCraftEvent.
        ItemStack placeholder = new ItemStack(Material.PLAYER_HEAD);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, placeholder);
        recipe.shape("AAA", "AHA", "AAA");
        recipe.setIngredient('A', Material.AMETHYST_SHARD);
        recipe.setIngredient('H', Material.PLAYER_HEAD);
        Bukkit.addRecipe(recipe);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
        for (UUID id : List.copyOf(originals.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) reset(p);
        }
    }

    /** Le craft copie le proprietaire de la tete du centre. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof ShapedRecipe sr) || !sr.getKey().equals(recipeKey)) return;

        ItemStack center = e.getInventory().getMatrix()[4];
        if (center == null || !(center.getItemMeta() instanceof SkullMeta src)) {
            e.getInventory().setResult(null);
            return;
        }
        PlayerProfile profile = src.getPlayerProfile();
        // Pas de tete vide, et pas de tete de transformation re-craftee
        if (profile == null || src.getPersistentDataContainer().has(markerKey)) {
            e.getInventory().setResult(null);
            return;
        }

        String name = profile.getName() != null ? profile.getName() : "???";

        ItemStack out = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) out.getItemMeta();
        meta.setPlayerProfile(profile);
        meta.displayName(Component.text("Tête de transformation : " + name, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Clic droit : devenir " + name, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak + clic droit : redevenir toi", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        out.setItemMeta(meta);
        e.getInventory().setResult(out);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return;
        if (!meta.getPersistentDataContainer().has(markerKey)) return;

        e.setCancelled(true); // evite de poser la tete
        Player player = e.getPlayer();

        if (player.isSneaking()) {
            reset(player);
            player.sendMessage(Component.text("Tu as retrouvé ton apparence.", NamedTextColor.GRAY));
            return;
        }

        PlayerProfile target = meta.getPlayerProfile();
        if (target == null) return;

        // update() recupere le skin (textures) en asynchrone
        target.update().thenAccept(full ->
                Bukkit.getScheduler().runTask(this, () -> disguise(player, full)));
    }

    private void disguise(Player player, PlayerProfile target) {
        if (!player.isOnline()) return;

        originals.putIfAbsent(player.getUniqueId(), player.getPlayerProfile());

        // On garde l'UUID du joueur (pour ne rien casser) mais on prend le nom et le skin de la cible.
        PlayerProfile disguised = Bukkit.createProfile(player.getUniqueId(), target.getName());
        disguised.setProperties(target.getProperties());
        player.setPlayerProfile(disguised);

        Component name = Component.text(target.getName() == null ? "???" : target.getName());
        player.displayName(name);
        player.playerListName(name);

        refreshForOthers(player);
        player.sendMessage(Component.text("Tu es maintenant " + target.getName() + " !", NamedTextColor.LIGHT_PURPLE));
    }

    private void reset(Player player) {
        PlayerProfile original = originals.remove(player.getUniqueId());
        if (original == null) return;
        player.setPlayerProfile(original);
        player.displayName(null);
        player.playerListName(null);
        refreshForOthers(player);
    }

    /** Force les autres joueurs a recharger l'apparence. */
    private void refreshForOthers(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) continue;
            other.hidePlayer(this, player);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) continue;
                other.showPlayer(this, player);
            }
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        reset(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        reset(e.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) {
            reset(p);
            p.sendMessage(Component.text("Tu as retrouvé ton apparence.", NamedTextColor.GRAY));
        }
        return true;
    }
}
