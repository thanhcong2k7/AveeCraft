package com.daaw.avee;

import java.util.HashMap;
import java.util.Map;

public class Defines {
    private static final Defines INSTANCE = new Defines();
    private final Map<String, Integer> drawables = new HashMap<>();

    private Defines() {
        drawables.put("circle_w_a_64", R.drawable.circle_w_a_64);
        drawables.put("ic_launcher", R.drawable.ic_launcher);
        drawables.put("loading_placeholder", R.drawable.loading_placeholder);
        drawables.put("path_edge", R.drawable.path_edge);
        drawables.put("random16", R.drawable.random16);
        drawables.put("square_w_a_64", R.drawable.square_w_a_64);
        drawables.put("square_ysides_w_a_64", R.drawable.square_ysides_w_a_64);
        drawables.put("particle_blur01", R.drawable.particle_blur01);
        drawables.put("particle_blur01_inv", R.drawable.particle_blur01_inv);
        drawables.put("particle_blur_inv", R.drawable.particle_blur01_inv);
        drawables.put("particle_blur01_more", R.drawable.particle_blur01_more);
        drawables.put("particle_circle_blur4", R.drawable.particle_circle_blur4);
        drawables.put("particle_circle_w_a_64", R.drawable.particle_circle_w_a_64);
        drawables.put("particle_sharp", R.drawable.particle_sharp);
        drawables.put("particle_sharp_128", R.drawable.particle_sharp_128);
        drawables.put("vignette80", R.drawable.vignette80);
        drawables.put("lens_flare", R.drawable.lens_flare_a_512);
        drawables.put("lens_flare_2", R.drawable.lens_flare_2_a_512);
        drawables.put("anim128_g_m10_15", R.drawable.anim128_g_m10_15);
        drawables.put("snowflake", R.drawable.snowflake);
        drawables.put("placeholderart", R.drawable.placeholderart4);
        drawables.put("transparent", R.drawable.transparent);
        drawables.put("white", R.drawable.white);
        drawables.put("black", R.drawable.black);
        drawables.put("rainbow128", R.drawable.rainbow128);
    }

    public static Defines getInstance() {
        return INSTANCE;
    }

    public int getDrawableResid(String name, int def) {
        if (name == null) return def;
        Integer id = drawables.get(name.trim());
        return id != null ? id : def;
    }
}
