package dev.impossibledragon;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An animated resource-pack model glued to an entity (Java Edition).
 * Every part is an ItemDisplay whose item has a custom model from the Java resource pack.
 * Bedrock players never see these: their pack replaces the entity model directly.
 */
final class ModelInstance {

    enum Mode { DRAGON, MOB, PROJECTILE }

    private final ModelManager mgr;
    private final Entity anchor;
    private final ModelDef def;
    private final Mode mode;
    private final float scale;
    private final float yOffset;
    private final boolean hideAnchorFromJava;

    private final List<ItemDisplay> displays = new ArrayList<>();
    private final Map<UUID, Set<UUID>> shown = new HashMap<>();
    private final Set<UUID> anchorHidden = new HashSet<>();

    private boolean removed = false;
    private boolean first = true;
    private double lastTheta = Double.NaN;
    private double lastY = Double.NaN;
    private float bank = 0, tilt = 0;
    private double headX = 0, headZ = 1;
    /** replica of the vanilla EnderDragon.flapTime clock (drives the middle head's jaw) */
    private double flapTime = 0;

    ModelInstance(ModelManager mgr, Entity anchor, ModelDef def, Mode mode, float scale, float yOffset,
                  boolean hideAnchorFromJava) {
        this.mgr = mgr;
        this.anchor = anchor;
        this.def = def;
        this.mode = mode;
        this.scale = scale;
        this.yOffset = yOffset;
        this.hideAnchorFromJava = hideAnchorFromJava;
    }

    boolean anchorIs(Entity e) {
        return anchor.getUniqueId().equals(e.getUniqueId());
    }

    World world() {
        return anchor.isValid() ? anchor.getWorld() : null;
    }

    boolean isRemoved() {
        return removed;
    }

    // ------------------------------------------------------------------ pose

    private Quaternionf root(Location l) {
        Vector h;
        switch (mode) {
            case DRAGON -> {
                Vector d = l.getDirection().setY(0);
                if (d.lengthSquared() < 1.0E-4) d = new Vector(headX, 0, headZ);
                d.normalize();
                // the vanilla dragon's head is on the side opposite to its yaw direction
                h = mgr.flipFacing() ? d : d.multiply(-1);
            }
            case PROJECTILE -> {
                h = anchor.getVelocity();
                if (h.lengthSquared() < 1.0E-5) h = l.getDirection();
                h = h.clone().normalize();
            }
            default -> {
                h = l.getDirection().setY(0);
                if (h.lengthSquared() < 1.0E-4) h = new Vector(headX, 0, headZ);
                h.normalize();
            }
        }
        double hx = h.getX(), hz = h.getZ();
        if (hx * hx + hz * hz < 1.0E-6) {
            hx = headX;
            hz = headZ;
        } else {
            headX = hx;
            headZ = hz;
        }
        double theta = Math.atan2(hx, hz);
        Quaternionf q = new Quaternionf().rotateY((float) theta);
        if (mode == Mode.PROJECTILE) {
            q.rotateX((float) -Math.asin(Math.max(-1, Math.min(1, h.getY()))));
        } else if (mode == Mode.DRAGON) {
            if (!Double.isNaN(lastTheta)) {
                double d = theta - lastTheta;
                while (d > Math.PI) d -= 2 * Math.PI;
                while (d < -Math.PI) d += 2 * Math.PI;
                float target = (float) Math.max(-0.5, Math.min(0.5, -d * 6));
                bank += (target - bank) * 0.25f;
            }
            if (!Double.isNaN(lastY)) {
                float target = (float) Math.max(-0.35, Math.min(0.35, -(l.getY() - lastY) * 0.35));
                tilt += (target - tilt) * 0.25f;
            }
            lastTheta = theta;
            lastY = l.getY();
            q.rotateZ(bank).rotateX(tilt);
        }
        return q;
    }

    // ------------------------------------------------------------------ lifecycle

    void update(long tick, boolean javaViewers, boolean sync, List<Player> players) {
        if (removed) return;
        if (!anchor.isValid()) {
            remove();
            return;
        }
        World w = anchor.getWorld();
        if (javaViewers && displays.isEmpty()) spawn(w);
        if (!javaViewers && !displays.isEmpty()) clearDisplays();

        Location l = anchor.getLocation();
        if (mode == Mode.DRAGON) advanceFlap();
        Location base = l.clone().add(0, yOffset, 0);
        double t = tick;
        Quaternionf root = root(l);

        if (!displays.isEmpty()) {
            Vector3f p = new Vector3f();
            for (int i = 0; i < def.parts.size() && i < displays.size(); i++) {
                ModelDef.Part part = def.parts.get(i);
                ItemDisplay d = displays.get(i);
                if (!d.isValid()) continue;
                p.set(part.center);
                Quaternionf q = new Quaternionf();
                for (ModelDef.Joint g : part.chain) {
                    Quaternionf gr = new Quaternionf().rotateAxis((float) g.angle(t, flapTime), g.axis.x, g.axis.y, g.axis.z);
                    p.sub(g.pivot);
                    gr.transform(p);
                    p.add(g.pivot);
                    q = new Quaternionf(gr).mul(q);
                }
                Vector3f wp = new Vector3f(p);
                root.transform(wp);
                wp.mul(scale);
                float s = part.k * scale;
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(root).mul(q),
                        new Vector3f(s, s, s), new Quaternionf()));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(ModelManager.PERIOD + 1);
                d.teleport(new Location(w, base.getX() + wp.x, base.getY() + wp.y, base.getZ() + wp.z));
            }
        }
        if (sync || first) {
            sync(players);
            first = false;
        }
    }

    /** same maths as the vanilla dragon's aiStep, run once per tick that passed since the last update */
    private void advanceFlap() {
        Vector v = anchor.getVelocity();
        double horiz = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        double f = 0.2 / (horiz * 10.0 + 1.0);
        f *= Math.pow(2.0, v.getY());
        flapTime += f * ModelManager.PERIOD;
    }

    private void spawn(World w) {
        Location at = anchor.getLocation();
        for (ModelDef.Part part : def.parts) {
            ItemStack stack = new ItemStack(Material.PAPER);
            ItemMeta meta = stack.getItemMeta();
            meta.setCustomModelData(part.cmd);
            stack.setItemMeta(meta);
            ItemDisplay d = w.spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setInterpolationDuration(ModelManager.PERIOD + 1);
                e.setTeleportDuration(ModelManager.PERIOD + 1);
                e.setViewRange(1.6f);
                e.setShadowRadius(0f);
                e.setBrightness(mgr.modelBrightness());
                e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                        new Vector3f(0.01f), new Quaternionf()));
            });
            displays.add(d);
        }
    }

    private void clearDisplays() {
        for (ItemDisplay d : displays) if (d.isValid()) d.remove();
        displays.clear();
        shown.clear();
    }

    void sync(List<Player> players) {
        Set<UUID> online = new HashSet<>();
        for (Player p : players) {
            online.add(p.getUniqueId());
            if (mgr.isBedrock(p)) continue; // Bedrock gets the model from its own resource pack
            if (hideAnchorFromJava && anchorHidden.add(p.getUniqueId())) {
                p.hideEntity(mgr.plugin(), anchor); // hide the vanilla skull, show the dragon head instead
            }
            if (displays.isEmpty()) continue;
            Set<UUID> s = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            for (ItemDisplay d : displays) {
                if (s.add(d.getUniqueId())) p.showEntity(mgr.plugin(), d);
            }
        }
        shown.keySet().retainAll(online);
    }

    void remove() {
        removed = true;
        clearDisplays();
    }
}
