package com.neuromuser.randomrespawn;

import java.util.HashMap;
import java.util.Map;

public class Config {
    public boolean defaultEnabled = true;
    public int respawnRange = 10000;
    public Map<String, Boolean> playerSettings = new HashMap<>();
}