#!/bin/bash

# Quick patcher script
# Usage: ./patcher.sh <command> <args?>

set -e

# Find gradle
if [ -f "./gradlew" ]; then
    GRADLE="./gradlew"
elif command -v gradle &> /dev/null; then
    GRADLE="gradle"
else
    echo "Error: Can't find gradle. Run this from the project root."
    exit 1
fi

# Check for an in-progress conflict resolution before destructive ops.
warn_if_in_progress() {
    if [ -d ".patch-conflicts" ]; then
        echo "⚠️  A patch is currently mid-resolution in .patch-conflicts/"
        echo "    Finish it with './patcher.sh finish' or abort with './patcher.sh abort' first."
        exit 1
    fi
}

case "$1" in
    init)
        warn_if_in_progress
        echo "=== Initializing ==="
        $GRADLE decompileAndApplyPatches
        echo "Done!"
        ;;

    fresh)
        warn_if_in_progress
        echo "=== Fresh Start ==="
        echo "This will wipe everything and decompile all over again."
        read -p "Continue? [y/N] " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            $GRADLE cleanDistributedSources cleanGenerated
            $GRADLE decompileAndApplyPatches
            echo "Finished!"
        fi
        ;;

    status|s)
        $GRADLE patchStatus
        ;;

    create|c)
        warn_if_in_progress
        if [ -z "$2" ]; then
            read -p "Patch name: " name
        else
            name="$2"
        fi
        $GRADLE createPatch -PpatchName="$name"
        ;;

    apply|a)
        warn_if_in_progress
        if [ -z "$2" ]; then
            $GRADLE listPatches
            echo ""
            read -p "Patch name: " name
        else
            name="$2"
        fi
        $GRADLE applyPatch -PpatchName="$name"
        ;;

    apply-all|aa)
        warn_if_in_progress
        $GRADLE applyAllPatches
        ;;

    apply-reject|ar)
        # Last-resort apply: writes successful hunks and leaves .rej files
        # for failing hunks in .patch-conflicts/.
        warn_if_in_progress
        if [ -z "$2" ]; then
            $GRADLE listPatches
            echo ""
            read -p "Patch name: " name
        else
            name="$2"
        fi
        $GRADLE applyPatchWithReject -PpatchName="$name"
        ;;

    finish|f)
        # Used after manually resolving conflicts in .patch-conflicts/.
        $GRADLE finishPatch
        ;;

    abort)
        # Throw away whatever's in .patch-conflicts/ without applying.
        $GRADLE abortPatch
        ;;

    sync)
        # Copy current module sources back over the generated baseline so
        # the next createPatch only diffs *new* changes. Run this after
        # finishing patches manually (in your IDE) outside the patcher.
        warn_if_in_progress
        echo "This will overwrite your decompiled baseline with the current module source."
        echo "Use this after manually applying patches in your IDE."
        read -p "Continue? [y/N] " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            $GRADLE syncSourceToGenerated
        fi
        ;;

    list|l)
        $GRADLE listPatches
        ;;

    inspect|i)
        $GRADLE inspectDecompiledStructure
        ;;

    clean)
        warn_if_in_progress
        echo "Clean options:"
        echo "  1) Module sources"
        echo "  2) Generated/decompiled"
        echo "  3) Both"
        read -p "Choice: " choice
        case $choice in
            1) $GRADLE cleanDistributedSources ;;
            2) $GRADLE cleanGenerated ;;
            3) $GRADLE cleanDistributedSources cleanGenerated ;;
        esac
        ;;

    save)
        warn_if_in_progress
        $GRADLE patchStatus
        echo ""
        read -p "Patch name (or blank to cancel): " name
        if [ -n "$name" ]; then
            $GRADLE createPatch -PpatchName="$name"
        fi
        ;;

    *)
        echo "Patcher commands:"
        echo ""
        echo "  init         - First time setup (decompile + distribute + patches)"
        echo "  fresh        - Wipe and start over"
        echo ""
        echo "  status, s    - Check what's been changed and what you can save as a new patch"
        echo "  save         - Check status then save as patch"
        echo "  create, c    - Create a patch from current modifications"
        echo "  apply, a     - Apply a single patch (3-way merge fallback)"
        echo "  apply-all,aa - Apply every patch in order"
        echo "  apply-reject,ar - Apply with --reject, leaves .rej files for failed hunks"
        echo "  list, l      - List patches"
        echo ""
        echo "  finish, f    - Finalize an in-progress patch after resolving conflicts in your IDE"
        echo "  abort        - Throw away an in-progress patch resolution"
        echo "  sync         - Snapshot current module source as the new decompile baseline"
        echo ""
        echo "  inspect, i   - Show decompiled structure"
        echo "  clean        - Clean up sources"
        echo ""
        echo "Workflow on a conflict:"
        echo "  1) './patcher.sh apply 003-foo' fails with conflict markers"
        echo "  2) Open .patch-conflicts/ in your IDE, fix <<<<<<< / ======= / >>>>>>>"
        echo "  3) './patcher.sh finish' to copy resolved files back"
        echo "  4) Optionally './patcher.sh create 003-foo' to refresh the patch file"
        echo ""
        echo "Examples:"
        echo "  ./patcher.sh init"
        echo "  ./patcher.sh status"
        echo "  ./patcher.sh create 000-my-patch"
        echo "  ./patcher.sh save"
        ;;
esac