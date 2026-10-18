#!/usr/bin/env bash
# Scaffold a new Minecraft mod from the official templates, with mod id + package renamed.
#
# Usage: scaffold.sh <neoforge|fabric> <modid> <java.package> [mc-version=26.3] [target-dir=./<modid>] ["Display Name"]
#   scaffold.sh neoforge blockyskylines com.cloudking.blockyskylines
#   scaffold.sh fabric   blockyskylines com.cloudking.blockyskylines 26.3 ./mod "Blocky Skylines"
#
# Templates:
#   NeoForge: https://github.com/NeoForgeMDKs/MDK-<mc>-ModDevGradle
#   Fabric:   https://github.com/FabricMC/fabric-example-mod (branch <mc> when it exists)
set -euo pipefail

loader=${1:?loader (neoforge|fabric)}
modid=${2:?mod id}
pkg=${3:?java package, e.g. com.you.modid}
mc=${4:-26.3}
dir=${5:-./$modid}
name=${6:-$modid}

[[ "$modid" =~ ^[a-z][a-z0-9_]{1,63}$ ]] || { echo "mod id must match [a-z][a-z0-9_]{1,63}" >&2; exit 1; }
[[ "$pkg" =~ ^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)+$ ]] || { echo "package must be lowercase dotted, e.g. com.you.$modid" >&2; exit 1; }
[[ -e "$dir" ]] && { echo "$dir already exists" >&2; exit 1; }

pkg_path=${pkg//.//}
esc() { printf '%s' "$1" | sed -e 's/[\/&|]/\\&/g'; }   # escape for sed replacement

case "$loader" in
  neoforge)
    git clone -q --depth 1 "https://github.com/NeoForgeMDKs/MDK-${mc}-ModDevGradle.git" "$dir"
    rm -rf "$dir/.git"
    cd "$dir"
    mkdir -p "src/main/java/$pkg_path"
    git_src="src/main/java/com/example/examplemod"
    mv "$git_src"/*.java "src/main/java/$pkg_path/"
    rmdir -p "$git_src" 2>/dev/null || true
    mv src/main/resources/assets/examplemod "src/main/resources/assets/$modid"
    grep -rlZ --include='*.java' 'com.example.examplemod' src | xargs -0 sed -i "s|com\.example\.examplemod|$(esc "$pkg")|g"
    grep -rlZ 'examplemod' src | xargs -0 sed -i "s|examplemod|$(esc "$modid")|g"
    sed -i -e "s|^mod_id=.*|mod_id=$(esc "$modid")|" \
           -e "s|^mod_name=.*|mod_name=$(esc "$name")|" \
           -e "s|^mod_group_id=.*|mod_group_id=$(esc "$pkg")|" gradle.properties
    ;;
  fabric)
    branch_args=()
    if git ls-remote --exit-code --heads https://github.com/FabricMC/fabric-example-mod.git "$mc" >/dev/null 2>&1; then
      branch_args=(-b "$mc")
    else
      echo "note: no fabric-example-mod branch '$mc'; using default branch" >&2
    fi
    git clone -q --depth 1 "${branch_args[@]}" https://github.com/FabricMC/fabric-example-mod.git "$dir"
    rm -rf "$dir/.git"
    cd "$dir"
    for set in main client; do
      if [[ -d "src/$set/java/com/example" ]]; then
        mkdir -p "src/$set/java/$pkg_path"
        cp -r "src/$set/java/com/example/." "src/$set/java/$pkg_path/"
        rm -rf "src/$set/java/com/example"
        rmdir "src/$set/java/com" 2>/dev/null || true
      fi
    done
    [[ -f src/main/resources/modid.mixins.json ]] && mv src/main/resources/modid.mixins.json "src/main/resources/$modid.mixins.json"
    [[ -f src/client/resources/modid.client.mixins.json ]] && mv src/client/resources/modid.client.mixins.json "src/client/resources/$modid.client.mixins.json"
    [[ -d src/main/resources/assets/modid ]] && mv src/main/resources/assets/modid "src/main/resources/assets/$modid"
    grep -rlZ 'com\.example' src | xargs -0 sed -i "s|com\.example|$(esc "$pkg")|g"
    sed -i -e "s|\"modid\"|\"$(esc "$modid")\"|g" -e "s|modid\.mixins\.json|$(esc "$modid").mixins.json|" \
           -e "s|modid\.client\.mixins\.json|$(esc "$modid").client.mixins.json|" \
           -e "s|assets/modid/|assets/$(esc "$modid")/|" -e "s|\"Example Mod\"|\"$(esc "$name")\"|" \
           src/main/resources/fabric.mod.json
    grep -rlZ --include='*.java' '"modid"' src | xargs -0 -r sed -i "s|\"modid\"|\"$(esc "$modid")\"|g"
    sed -i "s|\"modid\" {|\"$(esc "$modid")\" {|" build.gradle
    sed -i "s|^rootProject.name = .*|rootProject.name = '$(esc "$modid")'|" settings.gradle
    sed -i "s|^group=.*|group=$(esc "$pkg")|" gradle.properties
    ;;
  *) echo "loader must be neoforge or fabric" >&2; exit 1 ;;
esac

echo "Created $loader mod '$modid' ($pkg) for Minecraft $mc in $(pwd)"
echo "Leftover template identifiers (review these):"
grep -rn --include='*.java' --include='*.json' --include='*.toml' --include='*.gradle' --include='*.properties' \
  -e 'com\.example' -e 'examplemod' -e '"modid"' . | grep -v '^./gradle/' || echo "  none"
echo "Next: ./gradlew build   (needs network access to the loader maven and Mojang)"
