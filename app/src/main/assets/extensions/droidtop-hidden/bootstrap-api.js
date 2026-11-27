"use strict";

/* Runs only in the separately owned profile/process. Never change preferences in the ordinary Browser runtime. */
this.droidtopHiddenBootstrap = class extends ExtensionAPI {
  getAPI() {
    return { droidtopHiddenBootstrap: {
      async attest(expectedProfile, encodedPrefs) {
        const profile = Services.dirsvc.get("ProfD", Ci.nsIFile);
        profile.normalize();
        const required = JSON.parse(encodedPrefs);
        const actual = Object.create(null);
        const engineMajor = Number.parseInt(Services.appinfo.platformVersion, 10);
        let ok = profile.path === expectedProfile && engineMajor === 160;
        const defaults = Services.prefs.getDefaultBranch("");
        for (const [key, wanted] of Object.entries(required)) {
          try {
            // A user value for an unknown/removed preference would otherwise falsely attest a restriction that
            // this engine does not implement. The pinned engine must declare a default for every required key.
            if (defaults.getPrefType(key) === 0) { ok = false; continue; }
            actual[key] = typeof wanted === "boolean" ? Services.prefs.getBoolPref(key) : Services.prefs.getIntPref(key);
            if (actual[key] !== wanted) ok = false;
          } catch (error) { ok = false; }
        }
        if (ok) for (const key of Object.keys(required)) Services.prefs.lockPref(key);
        return JSON.stringify({ ok, profile: profile.path, engineMajor, prefs: actual });
      },
    } };
  }
};
