#!/bin/bash

# --- УНИВЕРСАЛЬНЫЙ СКРИПТ-ДИСПЕТЧЕР (v3.1 — Enter=Да, поддержка Git Bash) ---

# --- ФУНКЦИИ ---
wait_for_exit() {
    echo -e "\n----------------------------------------"
    read -p "Нажмите Enter для выхода..." < /dev/tty
}

# --- ИНИЦИАЛИЗАЦИЯ ---
if ! command -v jq &> /dev/null; then
    echo "Ошибка: jq не найден. Установите его."
    wait_for_exit
    exit 1
fi

echo "--- Вставьте JSON и нажмите Ctrl+D ---"
TMP_JSON=$(mktemp)
cat > "$TMP_JSON"

if ! jq empty "$TMP_JSON" 2>/dev/null; then
    echo "Ошибка: Невалидный JSON!"
    rm -f "$TMP_JSON"
    wait_for_exit
    exit 1
fi

BACKUP_DIR="/tmp/applyScriptSh/$(date +'%Y-%m-%d_%H-%M-%S')"
mkdir -p "$BACKUP_DIR"
echo -e "\n[INFO] Бэкапы для этой сессии будут сохранены в: $BACKUP_DIR"
BACKUP_CREATED=false

# --- ЭТАП 1: ПРИМЕНЕНИЕ ФАЙЛОВ С ПРЕДВАРИТЕЛЬНЫМ БЭКАПОМ ---
FILES_COUNT=$(jq '.files_to_create | length' "$TMP_JSON")

if [ "$FILES_COUNT" -gt 0 ]; then
    echo -e "\n[!] Нейросеть предлагает создать/обновить $FILES_COUNT файлов:"
    jq -r '.files_to_create[].file_path' "$TMP_JSON" | sed 's/^/  - /'
    echo -e "--------------------------------------------------------\n"

    APPLY_ALL=false
    for ((i=0; i<$FILES_COUNT; i++)); do
        FILE_PATH=$(jq -r ".files_to_create[$i].file_path" "$TMP_JSON")

        if [ "$APPLY_ALL" = false ]; then
            # (Enter=Да) - явное указание для пользователя
            echo -n "Применить '$FILE_PATH'? [Y/n/a/q] (Enter=Да, a=Все, q=Выход): "
            read -r choice < /dev/tty
            case "$choice" in
                a|A ) APPLY_ALL=true ;;
                q|Q ) break ;;
                n|N ) echo "  [Пропущено]"; continue ;;
                # Пустой ввод (Enter) или 'y' продолжают выполнение
                ""|y|Y ) ;;
                * ) echo "  [Пропущено]"; continue ;;
            esac
        fi

        if [ -f "$FILE_PATH" ]; then
            echo -n "  [BACKUP] Найден существующий файл. Создаю резервную копию... "
            mkdir -p "$(dirname "$BACKUP_DIR/$FILE_PATH")"
            cp "$FILE_PATH" "$BACKUP_DIR/$FILE_PATH"
            echo "OK"
            BACKUP_CREATED=true
        fi

        mkdir -p "$(dirname "$FILE_PATH")"
        jq -r ".files_to_create[$i].content" "$TMP_JSON" > "$FILE_PATH"
        echo "  [OK] Файл '$FILE_PATH' создан/обновлен."
    done
else
    MESSAGE=$(jq -r '.message // empty' "$TMP_JSON")
    if [ ! -z "$MESSAGE" ]; then
        echo -e "\n[INFO] Файлов для создания нет. Сообщение от ИИ:"
        echo "-> $MESSAGE"
    fi
fi

# --- ЭТАП 2: ОБРАБОТКА ЗАПРОСА ИНФОРМАЦИИ ---
REQUEST_TYPE=$(jq -r '.information_request.request_type // "NONE"' "$TMP_JSON")

if [ "$REQUEST_TYPE" != "NONE" ] && [ "$REQUEST_TYPE" != "null" ]; then
    echo -e "\n[!] Нейросеть запросила информацию о проекте."
    echo "    Режим: $REQUEST_TYPE"
    FILES_LIST=$(jq -r '.information_request.files | join(" ")' "$TMP_JSON")
    if [ "$REQUEST_TYPE" == "SPECIFIC_FILES" ]; then
        echo "    Файлы: $FILES_LIST"
    fi
    echo "--------------------------------------------------------"

    echo -n "Приступить к сбору информации? [Y/n]: "
    read -r choice < /dev/tty

    # Если choice пустой (нажат Enter) или 'y/Y', то выполняем
    if [[ -z "$choice" || "$choice" =~ ^[Yy]$ ]]; then
        if [ -f "./helper.sh" ]; then
            ./helper.sh "$REQUEST_TYPE" $FILES_LIST
            echo -e "\n[ВАЖНО] Сбор данных завершен. Результат в 'project_full_source.txt'."
        else
            echo "Ошибка: helper.sh не найден в текущей директории."
        fi
    else
        echo "Сбор информации пропущен."
    fi
fi

# --- ЗАВЕРШЕНИЕ ---
rm -f "$TMP_JSON"
echo -e "\n[Готово] Все действия выполнены."

if [ "$BACKUP_CREATED" = true ]; then
    echo -n "Открыть папку с резервными копиями ($BACKUP_DIR)? [Y/n]: "
    read -r choice < /dev/tty
    if [[ -z "$choice" || "$choice" =~ ^[Yy]$ ]]; then
        OPEN_CMD=""
        # Проверяем ОС
        if [[ "$(uname)" == "Darwin" ]]; then # macOS
            OPEN_CMD="open"
        # Проверка на MINGW (Git Bash) или CYGWIN
        elif [[ "$(uname -s)" =~ "MINGW" || "$(uname -s)" =~ "CYGWIN" ]]; then
            # Команда 'start' отлично работает в Git Bash для открытия папок
            echo "Открываю папку в Проводнике Windows..."
            start "$BACKUP_DIR"
        elif [[ "$(uname -s)" =~ "Linux" ]]; then # Linux
            if grep -qE "(Microsoft|WSL)" /proc/version &> /dev/null; then # WSL
                 BACKUP_DIR_WIN=$(wslpath -w "$BACKUP_DIR")
                 explorer.exe "$BACKUP_DIR_WIN"
                 echo "Открываю в Проводнике Windows через WSL..."
            else # Native Linux
                 OPEN_CMD="xdg-open"
            fi
        fi

        if [[ -n "$OPEN_CMD" ]]; then
            echo "Открываю папку..."
            $OPEN_CMD "$BACKUP_DIR" &> /dev/null
        elif [[ -z "$OPEN_CMD" && ! "$(uname -s)" =~ "MINGW" && ! "$(uname -s)" =~ "CYGWIN" && ! -f "/proc/version" ]]; then
             echo "Не удалось определить команду для открытия папки в вашей ОС."
        fi
    fi
fi

wait_for_exit