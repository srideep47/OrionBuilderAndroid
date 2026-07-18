package com.legion.orion.feature.editor

import android.content.Context
import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import org.eclipse.tm4e.core.registry.IThemeSource

/** A selectable language mode. TextMate is used when [scope] is available; all others use Orion's lexer. */
data class EditorLanguageSpec(
    val id: String,
    val label: String,
    val extensions: Set<String> = emptySet(),
    val exactNames: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    val scope: String? = null,
)

object EditorLanguageCatalog {
    private fun words(value: String) = value.split(' ').filter(String::isNotBlank).toSet()

    private val cStyle = words(
        "abstract as async await break case catch class const continue default delete do else enum " +
            "export extends false final finally for from function if implements import in instanceof " +
            "interface internal let namespace new null override package private protected public readonly " +
            "return sealed static struct super switch synchronized this throw throws trait true try type " +
            "typeof undefined using val var virtual void volatile when while with yield",
    )
    private val python = words(
        "and as assert async await break class continue def del elif else except False finally for from " +
            "global if import in is lambda None nonlocal not or pass raise return True try while with yield",
    )
    private val shell = words(
        "alias bg bind break builtin case cd command continue declare dirs disown do done echo elif else " +
            "enable esac eval exec exit export false fc fg fi for function getopts hash help history if in " +
            "jobs kill let local logout mapfile popd printf pushd pwd read readonly return select set shift " +
            "source suspend test then time times trap true type typeset ulimit umask unalias unset until wait while",
    )
    private val sql = words(
        "add all alter and any as asc backup begin between by case check column constraint create database " +
            "default delete desc distinct drop else end exists foreign from full group having in index inner " +
            "insert into is join key left like limit not null on or order outer primary procedure references " +
            "right row select set table top truncate union unique update values view when where with",
    )

    val all: List<EditorLanguageSpec> = listOf(
        EditorLanguageSpec("plain", "Plain text", extensions = setOf("txt", "log"), lineComments = emptyList(), blockComment = null),
        EditorLanguageSpec("generic", "Generic code", keywords = cStyle),
        EditorLanguageSpec("kotlin", "Kotlin", setOf("kt", "kts"), keywords = cStyle, scope = "source.kotlin"),
        EditorLanguageSpec("java", "Java", setOf("java"), keywords = cStyle, scope = "source.java"),
        EditorLanguageSpec("javascript", "JavaScript / TypeScript", setOf("js", "jsx", "mjs", "cjs", "ts", "tsx"), keywords = cStyle, scope = "source.js"),
        EditorLanguageSpec("html", "HTML", setOf("html", "htm", "vue", "svelte", "astro"), keywords = cStyle, lineComments = emptyList(), blockComment = "<!--" to "-->", scope = "text.html.basic"),
        EditorLanguageSpec("xml", "XML", setOf("xml", "svg", "xsl", "xslt", "plist"), lineComments = emptyList(), blockComment = "<!--" to "-->", scope = "text.xml"),
        EditorLanguageSpec("markdown", "Markdown", setOf("md", "mdx", "markdown"), lineComments = emptyList(), blockComment = "<!--" to "-->", scope = "text.html.markdown"),
        EditorLanguageSpec("python", "Python", setOf("py", "pyw", "pyi"), keywords = python, lineComments = listOf("#"), blockComment = null, scope = "source.python"),
        EditorLanguageSpec("lua", "Lua", setOf("lua"), keywords = words("and break do else elseif end false for function goto if in local nil not or repeat return then true until while"), lineComments = listOf("--"), blockComment = "--[[" to "]]", scope = "source.lua"),
        EditorLanguageSpec("json", "JSON / JSONC", setOf("json", "jsonc", "webmanifest", "map"), keywords = words("true false null"), lineComments = listOf("//")),
        EditorLanguageSpec("yaml", "YAML / TOML / INI", setOf("yaml", "yml", "toml", "ini", "cfg", "conf", "properties"), keywords = words("true false null yes no on off include extends"), lineComments = listOf("#", ";"), blockComment = null),
        EditorLanguageSpec("css", "CSS / Sass / Less", setOf("css", "scss", "sass", "less"), keywords = words("@charset @container @font-face @import @keyframes @layer @media @page @supports from to important inherit initial revert unset var calc")),
        EditorLanguageSpec("c_cpp", "C / C++ / Objective-C", setOf("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm"), keywords = cStyle + words("alignas alignof auto bool char constexpr decltype double explicit extern float friend inline int long mutable noexcept register short signed sizeof template thread_local typedef typename union unsigned wchar_t")),
        EditorLanguageSpec("csharp", "C#", setOf("cs"), keywords = cStyle + words("add alias ascending base bool byte checked decimal delegate descending dynamic event explicit fixed foreach get global implicit int lock long object out params partial ref remove sbyte short stackalloc string uint ulong unchecked unsafe ushort")),
        EditorLanguageSpec("go", "Go", setOf("go"), keywords = words("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var")),
        EditorLanguageSpec("rust", "Rust", setOf("rs"), keywords = words("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while")),
        EditorLanguageSpec("swift", "Swift", setOf("swift"), keywords = cStyle + words("actor associatedtype convenience deinit didSet extension fallthrough fileprivate guard indirect init inout lazy mutating nonisolated optional required some subscript unowned weak willSet")),
        EditorLanguageSpec("dart", "Dart", setOf("dart"), keywords = cStyle + words("covariant deferred dynamic external factory get library mixin on operator part rethrow set show sync typedef")),
        EditorLanguageSpec("php", "PHP", setOf("php", "phtml"), keywords = cStyle + words("callable clone declare echo empty enddeclare endfor endforeach endif endswitch endwhile eval exit global include include_once list require require_once"), lineComments = listOf("//", "#")),
        EditorLanguageSpec("ruby", "Ruby", setOf("rb", "rake", "gemspec"), exactNames = setOf("Gemfile", "Rakefile"), keywords = words("alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield"), lineComments = listOf("#"), blockComment = "=begin" to "=end"),
        EditorLanguageSpec("shell", "Shell", setOf("sh", "bash", "zsh", "fish"), exactNames = setOf("gradlew"), keywords = shell, lineComments = listOf("#"), blockComment = null),
        EditorLanguageSpec("powershell", "PowerShell", setOf("ps1", "psm1", "psd1"), keywords = shell + words("begin process end param filter foreach-object where-object function switch trap class enum using"), lineComments = listOf("#"), blockComment = "<#" to "#>"),
        EditorLanguageSpec("sql", "SQL", setOf("sql"), keywords = sql, lineComments = listOf("--")),
        EditorLanguageSpec("gradle", "Gradle / Groovy", setOf("gradle", "groovy", "gvy"), keywords = cStyle + words("def as assert in trait")),
        EditorLanguageSpec("build", "Build scripts", setOf("mk", "cmake"), exactNames = setOf("Makefile", "CMakeLists.txt", "Dockerfile"), keywords = shell + words("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL"), lineComments = listOf("#"), blockComment = null),
        EditorLanguageSpec("git", "Git / diff", setOf("diff", "patch"), exactNames = setOf(".gitignore", ".gitattributes", ".gitmodules"), keywords = words("diff index new deleted file mode rename from to Binary files differ"), lineComments = listOf("#"), blockComment = null),
    )

    private val byId = all.associateBy(EditorLanguageSpec::id)
    private val plain = byId.getValue("plain")
    private val generic = byId.getValue("generic")

    fun byId(id: String?): EditorLanguageSpec = byId[id] ?: generic

    fun detect(path: String?): EditorLanguageSpec {
        val name = path?.substringAfterLast('/').orEmpty()
        all.firstOrNull { name in it.exactNames }?.let { return it }
        val extension = name.substringAfterLast('.', "").lowercase()
        all.firstOrNull { extension.isNotEmpty() && extension in it.extensions }?.let { return it }
        return if (extension.isEmpty()) plain else generic
    }
}

/** Initializes the global TextMate registries once and creates isolated language instances per editor. */
object EditorLanguageSupport {
    @Volatile private var initialized = false
    @Volatile private var initializationFailed = false

    @Synchronized
    private fun initialize(context: Context): Boolean {
        if (initialized) return true
        if (initializationFailed) return false
        return runCatching {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val themePath = "textmate/darcula.json"
            ThemeRegistry.getInstance().loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream(themePath),
                        themePath,
                        null,
                    ),
                    "orion-darcula",
                ).apply { isDark = true },
            )
            ThemeRegistry.getInstance().setTheme("orion-darcula")
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            initialized = true
            true
        }.getOrElse {
            initializationFailed = true
            false
        }
    }

    fun colorScheme(context: Context): EditorColorScheme = if (initialize(context)) {
        runCatching { TextMateColorScheme.create(ThemeRegistry.getInstance()) }.getOrElse { SchemeDarcula() }
    } else {
        SchemeDarcula()
    }

    fun create(context: Context, spec: EditorLanguageSpec): Language {
        if (spec.id == "plain") return EmptyLanguage()
        return spec.scope?.takeIf { initialize(context) }
            ?.let { scope -> runCatching { TextMateLanguage.create(scope, true) }.getOrNull() }
            ?: UniversalCodeLanguage(spec)
    }
}

/** Fast broad fallback for formats without a bundled TextMate grammar. */
private class UniversalCodeLanguage(private val spec: EditorLanguageSpec) : EmptyLanguage() {
    private val analyzer = UniversalAnalyzeManager(spec)
    private val completion = IdentifierAutoComplete(spec.keywords.toTypedArray()).apply {
        setKeywords(spec.keywords.toTypedArray(), false)
    }

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val prefix = CompletionHelper.computePrefix(content, position) { it.isLetterOrDigit() || it == '_' || it == '$' }
        completion.requireAutoComplete(content, position, prefix, publisher, analyzer.data)
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val before = content.getLine(line).substring(0, column)
        val opens = before.count { it == '{' || it == '[' || it == '(' }
        val closes = before.count { it == '}' || it == ']' || it == ')' }
        return (opens - closes).coerceAtLeast(0) * 4
    }

    override fun getSymbolPairs(): SymbolPairMatch = SymbolPairMatch.DefaultSymbolPairs()

    override fun destroy() {
        analyzer.destroy()
    }
}

private class UniversalAnalyzeManager(
    private val spec: EditorLanguageSpec,
) : SimpleAnalyzeManager<IdentifierAutoComplete.Identifiers>() {
    override fun analyze(
        text: StringBuilder,
        delegate: Delegate<IdentifierAutoComplete.Identifiers>,
    ): Styles {
        val spans = MappedSpans.Builder()
        val identifiers = IdentifierAutoComplete.DisposableIdentifiers().apply { beginBuilding() }
        if (text.length > MAX_HIGHLIGHT_CHARS) {
            val lastLine = text.count { it == '\n' }
            spans.determine(lastLine)
            identifiers.finishBuilding()
            delegate.setData(identifiers)
            return Styles(spans.build())
        }

        var lineIndex = 0
        var lineStart = 0
        var inBlockComment = false
        while (lineStart <= text.length) {
            if (delegate.isCancelled) break
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val line = text.substring(lineStart, lineEnd).removeSuffix("\r")
            var i = 0
            spans.addIfNeeded(lineIndex, 0, if (inBlockComment) EditorColorScheme.COMMENT.toLong() else EditorColorScheme.TEXT_NORMAL.toLong())
            while (i < line.length) {
                if (inBlockComment) {
                    val end = spec.blockComment?.second
                    val close = end?.let { line.indexOf(it, i) } ?: -1
                    if (close < 0) break
                    i = close + end!!.length
                    inBlockComment = false
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                val block = spec.blockComment
                if (block != null && line.startsWith(block.first, i)) {
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.COMMENT.toLong())
                    val close = line.indexOf(block.second, i + block.first.length)
                    if (close < 0) {
                        inBlockComment = true
                        break
                    }
                    i = close + block.second.length
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                val lineComment = spec.lineComments.firstOrNull { line.startsWith(it, i) }
                if (lineComment != null) {
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.COMMENT.toLong())
                    break
                }

                val ch = line[i]
                if (ch == '"' || ch == '\'' || ch == '`') {
                    val quote = ch
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.LITERAL.toLong())
                    i++
                    var escaped = false
                    while (i < line.length) {
                        val current = line[i++]
                        if (current == quote && !escaped) break
                        escaped = current == '\\' && !escaped
                        if (current != '\\') escaped = false
                    }
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                if (ch.isDigit()) {
                    val start = i++
                    while (i < line.length && (line[i].isLetterOrDigit() || line[i] in ".xX_+-")) i++
                    spans.addIfNeeded(lineIndex, start, EditorColorScheme.LITERAL.toLong())
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                if (ch == '@' && i + 1 < line.length && isIdentifierPart(line[i + 1])) {
                    val start = i++
                    while (i < line.length && isIdentifierPart(line[i])) i++
                    spans.addIfNeeded(lineIndex, start, EditorColorScheme.ANNOTATION.toLong())
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                if (isIdentifierStart(ch)) {
                    val start = i++
                    while (i < line.length && isIdentifierPart(line[i])) i++
                    val word = line.substring(start, i)
                    if (word.length > 1 && word !in spec.keywords) identifiers.addIdentifier(word)
                    val next = line.indexOfFirstFrom(i) { !it.isWhitespace() }
                    val style = when {
                        word in spec.keywords -> EditorColorScheme.KEYWORD
                        word in LITERALS -> EditorColorScheme.LITERAL
                        next >= 0 && line[next] == '(' -> EditorColorScheme.FUNCTION_NAME
                        word.firstOrNull()?.isUpperCase() == true -> EditorColorScheme.IDENTIFIER_NAME
                        next >= 0 && line[next] == ':' -> EditorColorScheme.ATTRIBUTE_NAME
                        else -> EditorColorScheme.IDENTIFIER_VAR
                    }
                    spans.addIfNeeded(lineIndex, start, style.toLong())
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.TEXT_NORMAL.toLong())
                    continue
                }

                if (!ch.isWhitespace()) {
                    spans.addIfNeeded(lineIndex, i, EditorColorScheme.OPERATOR.toLong())
                    spans.addIfNeeded(lineIndex, i + 1, EditorColorScheme.TEXT_NORMAL.toLong())
                }
                i++
            }
            lineIndex++
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        spans.determine((lineIndex - 1).coerceAtLeast(0))
        identifiers.finishBuilding()
        delegate.setData(identifiers)
        return Styles(spans.build())
    }

    private fun isIdentifierStart(ch: Char) = ch.isLetter() || ch == '_' || ch == '$'
    private fun isIdentifierPart(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '$' || ch == '-'
    private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }

    companion object {
        private const val MAX_HIGHLIGHT_CHARS = 2_000_000
        private val LITERALS = setOf("true", "false", "null", "nil", "None", "True", "False", "undefined", "NaN")
    }
}
