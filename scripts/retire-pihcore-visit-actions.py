#!/usr/bin/env python3
"""Drop pihcore visit actions whose HTML form is retired.

pihcore builds its visit actions in CustomAppLoaderFactory.getExtensions(),
which is compiled into pihcore-api inside the pihcore omod. The forms are not
declared in any app JSON, so removing a form from the distro leaves the button
in place, pointing at a formUuid that no longer resolves.

This rewrites a CFR decompile of that class in place, deleting every
visitAction() registration whose form is listed in content/exclusions.txt,
together with the locals and country branches that existed only to feed those
registrations. Registrations for forms that were never shipped are left alone:
pihcore carries those on purpose and the visit UI already tolerates them.
Non-visit registrations (overallAction, fragmentExtension) are never touched.

Usage: retire-pihcore-visit-actions.py <decompiled.java> <exclusions.txt>
"""

import re
import sys

EXCLUSION_PREFIX = "pih/htmlforms/"

# A form resource reaching a local, either the declaration
#   String x = PihCoreUtil.getFormResource((String)"anc.xml");
# or a later reassignment
#   x = PihCoreUtil.getFormResource((String)"ncd-followup.xml");
ASSIGN_RE = re.compile(r'(?:(String) )?(\w+) = PihCoreUtil\.getFormResource\(\(String\)"([^"]+)"\);')
#   x = x + "&returnUrl=...";
RETURN_URL_RE = re.compile(r'(\w+) = \1 \+ "&returnUrl=')
#   Extension x = CustomAppLoaderUtil.visitAction(
LOCAL_RE = re.compile(r'Extension (\w+) = CustomAppLoaderUtil\.visitAction\(')
# A method declaration. Its body may legitimately empty out; the method itself
# stays, because getExtensions() still calls it behind a component flag.
METHOD_RE = re.compile(r'^    (?:public|private|protected|static|final|synchronized)\b.*\)\s*\{\s*$')


def excluded_forms(path):
    """Form paths, relative to the htmlforms dir, retired by the exclusion list."""
    forms = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            entry = line.strip()
            if entry.startswith(EXCLUSION_PREFIX) and entry.endswith(".xml"):
                forms.add(entry[len(EXCLUSION_PREFIX):])
    return forms


def walk(lines, kill):
    """Form each local holds at each line, ignoring deleted lines.

    Reassignments win over the declaration, so the value a registration sees is
    the one its own line depends on.
    """
    held = {}
    at = []
    for i, line in enumerate(lines):
        at.append(dict(held))
        found = ASSIGN_RE.search(line)
        if found and i not in kill:
            held[found.group(2)] = found.group(3)
    return at


def form_of(line, held):
    """The form a registration links to, resolving a hoisted local if needed."""
    inline = re.findall(r'getFormResource\(\(String\)"([^"]+)"', line)
    if inline:
        return inline[0]
    for name, value in held.items():
        if re.search(r'\b%s\b' % re.escape(name), line):
            return value
    return None


def follows(line, local):
    """True when a line registers a previously declared action."""
    if line.strip() == "this.extensions.add(%s);" % local:
        return True
    return re.match(
        r'this\.extensions\.add\(CustomAppLoaderUtil\.cloneAs\w+\(\(Extension\)%s\)\);$' % local,
        line.strip(),
    ) is not None


def kill_registrations(lines, excluded):
    """Delete visitAction registrations whose form is excluded."""
    kill = set()
    for i, (line, held) in enumerate(zip(lines, walk(lines, set()))):
        if "CustomAppLoaderUtil.visitAction(" not in line:
            continue
        if form_of(line, held) not in excluded:
            continue
        kill.add(i)
        local = LOCAL_RE.search(line)
        if not local:
            continue
        j = i + 1
        while j < len(lines) and follows(lines[j], local.group(1)):
            kill.add(j)
            j += 1
    return kill


def kill_form_locals(lines, excluded, kill, replace):
    """Delete the form resources and return-url decorations of retired forms.

    A local may serve both a retired and a shipped form, so the retired
    assignment goes and the live one stays. When the declaration is the line
    dropped, the next surviving assignment is promoted to declare the type.
    """
    # Decide the decorations against the values the locals hold upstream: an
    # assignment below this point may be retired itself.
    before = walk(lines, set())
    for i, line in enumerate(lines):
        found = ASSIGN_RE.search(line)
        if found and i not in kill and found.group(3) in excluded:
            kill.add(i)

    for i, line in enumerate(lines):
        if i in kill:
            continue
        found = RETURN_URL_RE.search(line)
        # The decoration applies to whatever the local holds at this line.
        if found and before[i].get(found.group(1)) in excluded:
            kill.add(i)
            if i > 0 and lines[i - 1].rstrip().endswith("{"):
                kill.add(i - 1)
            if i + 1 < len(lines) and lines[i + 1].strip() == "}":
                kill.add(i + 1)

    for i, line in enumerate(lines):
        found = ASSIGN_RE.search(line)
        if not (found and found.group(1) and i in kill):
            continue
        name = found.group(2)
        for j in range(i + 1, len(lines)):
            later = ASSIGN_RE.search(lines[j])
            if later and later.group(2) == name and j not in kill:
                if not later.group(1):
                    replace[j] = lines[j].replace(
                        "%s = PihCoreUtil" % name, "String %s = PihCoreUtil" % name, 1)
                break
    return kill


def kill_empty_blocks(lines, kill, replace):
    """Delete the if/else branches left with no statements.

    In an if/else-if/else chain the closing brace of a deleted branch re-links
    to the branch before it, so deleting just the branch header is normally
    enough. Two cases need more: a chain that starts with an empty branch has to
    promote the next branch to the head of the chain, and a chain whose last
    branch is empty has nothing left to re-link to, so its closing brace goes
    as well.
    """
    for i, line in enumerate(lines):
        if i in kill or not line.rstrip().endswith("{") or METHOD_RE.match(line):
            continue
        indent = len(line) - len(line.lstrip())
        close = next(
            (j for j in range(i + 1, len(lines))
             if len(lines[j]) - len(lines[j].lstrip()) == indent
             and lines[j].strip().startswith("}")),
            None,
        )
        if close is None or any(j not in kill for j in range(i + 1, close)):
            continue
        body = lines[close].strip()
        if body == "}":
            kill.add(i)
            if not line.lstrip().startswith("}"):
                kill.add(close)
        elif body.startswith("} else if"):
            if line.lstrip().startswith("}"):
                kill.add(i)
            else:
                # Promote the next branch to the head of the chain.
                kill.add(i)
                replace[close] = lines[close].replace("} else if", "if", 1)
    return kill


def prune(lines, excluded):
    kill = set()
    replace = {}
    kill.update(kill_registrations(lines, excluded))
    kill_form_locals(lines, excluded, kill, replace)
    kill_empty_blocks(lines, kill, replace)
    return [replace.get(i, l) for i, l in enumerate(lines) if i not in kill], kill


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip())
    path, exclusions = sys.argv[1], sys.argv[2]
    excluded = excluded_forms(exclusions)

    original = open(path, encoding="utf-8").read().split("\n")
    lines = original
    removed = []
    while True:
        kept, kill = prune(lines, excluded)
        if len(kept) == len(lines):
            break
        removed += [(i, lines[i].strip()) for i in sorted(kill) if lines[i].strip()]
        lines = kept

    left = sorted(f for f in excluded if '"%s"' % f in "\n".join(lines))
    if left:
        sys.exit("ERROR: still references retired forms: %s" % ", ".join(left))

    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))

    print("   excluded forms: %d" % len(excluded))
    print("   lines:          %d -> %d" % (len(original), len(lines)))
    print("   registrations:  %d removed"
          % sum(1 for _, t in removed if "visitAction(" in t))
    print("   other lines:    %d removed" % sum(1 for _, t in removed if "visitAction(" not in t))
    for lineno, text in removed:
        print("     %4d: %s" % (lineno, text[:92]))


if __name__ == "__main__":
    main()
