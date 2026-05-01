#!/bin/bash

# --- УНИВЕРСАЛЬНЫЙ СКРИПТ СБОРА КОНТЕКСТА ---

OUTPUT="project_full_source.txt"
ALLOWED_EXT="\.(dart|kt|java|go|xml|gradle|kts|properties|toml|yaml|yml|json|md|aidl|c|h|s|S|sh|bat|mk|modulemap)$|CMakeLists\.txt|AndroidManifest\.xml|go\.mod|go\.sum|dockerignore|gitignore|clang-format"
EXCLUDE_PATTERN="\.idea/|\.git/|\.gradle/|\.dart_tool/|build/|out/|bin/|pkg/|node_modules/|ios/Pods/|\.class$|\.jar$|\.so$|\.o$|\.a$|\.dll$|\.png$|\.ttf$|\.idx$|\.pack$|\.rev$|\.dat$|generated/"
LINE_THRESHOLD=500

get_clean_file_list() {
    find . -type f 2>/dev/null | grep -E "$ALLOWED_EXT" | grep -vE "$EXCLUDE_PATTERN" | sed 's|^\./||' | sort
}

format_path_list() {
    sed 's|^|└── |'
}

# --- ОПРЕДЕЛЕНИЕ РЕЖИМА РАБОТЫ ---
if [ "$#" -gt 0 ]; then
    MODE_ARG=$1
    shift
    FILES_ARG="$@"
else
    echo "Select collection mode:"
    echo "1) Full Project (Tree + All Code)"
    echo "2) Specific Files (Tree + Selected Code)"
    echo "3) Tree Only (Cleaned Logic Structure)"
    echo "4) Large Files Tree (Only files > $LINE_THRESHOLD lines)"
    read -p "Choice [1, 2, 3 or 4]: " choice

    case "$choice" in
        1) MODE_ARG="FULL_PROJECT" ;;
        2) MODE_ARG="SPECIFIC_FILES" ;;
        3) MODE_ARG="TREE_ONLY" ;;
        4) MODE_ARG="LARGE_FILES_TREE" ;;
        *) echo "Invalid choice."; exit 1 ;;
    esac

    if [ "$MODE_ARG" == "SPECIFIC_FILES" ]; then
        echo "Paste the list of files (from chat), separated by spaces:"
        read -p "Files: " FILES_ARG
    fi
fi

# --- ОСНОВНАЯ ЛОГИКА ---
echo "Generating project summary..." > "$OUTPUT"
echo "Mode: $MODE_ARG, Date: $(date)" >> "$OUTPUT"
echo "================================================" >> "$OUTPUT"

case "$MODE_ARG" in
    "FULL_PROJECT")
        echo "Collecting tree and all source code..."
        FILES_TO_PROCESS=$(get_clean_file_list)
        echo "Logic Structure:" >> "$OUTPUT"
        echo "$FILES_TO_PROCESS" | format_path_list >> "$OUTPUT"
        ;;
    "SPECIFIC_FILES")
        echo "Collecting specific files..."
        # Прогоняем через cygpath на случай, если скопированы Windows пути из IDE, удаляем \r
        FILES_TO_PROCESS=$(echo "$FILES_ARG" | tr ' ' '\n' | sed 's/\r//g' | xargs -r cygpath -u 2>/dev/null)
        echo "Selected Files:" >> "$OUTPUT"
        echo "$FILES_TO_PROCESS" | format_path_list >> "$OUTPUT"
        ;;
    "TREE_ONLY")
        echo "Collecting tree structure only..."
        echo "Cleaned Logic Structure:" >> "$OUTPUT"
        get_clean_file_list | format_path_list >> "$OUTPUT"
        echo "Done! Tree generated in $OUTPUT"
        exit 0
        ;;
    "LARGE_FILES_TREE")
        echo "Scanning for large files (>$LINE_THRESHOLD lines)..."
        LARGE_FILES=""
        while IFS= read -r file; do
            if [ -f "$file" ]; then
                line_count=$(wc -l < "$file")
                if [ "$line_count" -gt "$LINE_THRESHOLD" ]; then
                    LARGE_FILES+="$file ($line_count lines)"$'\n'
                fi
            fi
        done < <(get_clean_file_list)

        echo "Files with > $LINE_THRESHOLD lines:" >> "$OUTPUT"
        [ -z "$LARGE_FILES" ] && echo "No such files found." >> "$OUTPUT" || echo "$LARGE_FILES" | sed '/^$/d' | format_path_list >> "$OUTPUT"
        echo "Done! List saved in $OUTPUT"
        exit 0
        ;;
esac

# --- СБОР СОДЕРЖИМОГО (для FULL_PROJECT и SPECIFIC_FILES) ---
echo -e "\n================================================\nFILE CONTENTS\n================================================" >> "$OUTPUT"

IFS=$'\n'
for file in $FILES_TO_PROCESS; do
    file=$(echo "$file" | xargs) # Обрезаем лишние пробелы
    # grep -qI . пропускает бинарники, проверяем чтобы не забивать txt файл мусором
    if [ -n "$file" ] && [ -f "$file" ] && grep -qI . "$file" 2>/dev/null; then
        echo "Adding content of: $file"
        echo -e "\n--\`$file\`--\n" >> "$OUTPUT"
        cat "$file" >> "$OUTPUT"
        echo -e "\n------------------------------------------------" >> "$OUTPUT"
    fi
done

echo "Done! All content saved in: $OUTPUT"

