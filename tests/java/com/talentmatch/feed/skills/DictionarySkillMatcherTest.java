package com.talentmatch.feed.skills;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.skills.DictionarySkillMatcher.Term;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Spec §9.1 item 7, §4.6: boundary rules, case rules, ambiguous names, aliases. */
class DictionarySkillMatcherTest {

    private static final Map<String, UUID> IDS = new LinkedHashMap<>();

    private static UUID id(String skill) {
        return IDS.computeIfAbsent(skill, k -> UUID.nameUUIDFromBytes(k.getBytes()));
    }

    /** "Name" or "Name=alias1,alias2". */
    private static DictionarySkillMatcher matcher(List<String> ambiguous, String... skills) {
        List<Term> terms = new ArrayList<>();
        for (String s : skills) {
            String[] parts = s.split("=", 2);
            String name = parts[0];
            terms.add(new Term(id(name), name, name));
            if (parts.length == 2) {
                for (String alias : parts[1].split(",")) {
                    terms.add(new Term(id(name), name, alias));
                }
            }
        }
        return new DictionarySkillMatcher(terms, ambiguous);
    }

    private static DictionarySkillMatcher matcher(String... skills) {
        return matcher(DictionarySkillMatcher.DEFAULT_AMBIGUOUS_NAMES, skills);
    }

    private static List<String> names(List<SkillMention> mentions) {
        return mentions.stream().map(SkillMention::name).toList();
    }

    @Test
    void javaIsNotFoundInsideJavaScript() {
        DictionarySkillMatcher m = matcher("Java", "JavaScript");
        assertThat(names(m.match("We write JavaScript and TypeScript."))).containsExactly("JavaScript");
        assertThat(names(m.match("Java and JavaScript."))).containsExactly("Java", "JavaScript");
        assertThat(names(m.match("Java-based services, Java/Kotlin."))).containsExactly("Java");
    }

    @Test
    void symbolNamesMatchWithTheirPunctuation() {
        DictionarySkillMatcher m = matcher("C", "C++", "C#", ".NET", "Node.js", "R");
        assertThat(names(m.match("We use C++, C# and .NET daily. Node.js for tooling.")))
                .containsExactly("C++", "C#", ".NET", "Node.js");
        assertThat(names(m.match("Plain C and R."))).containsExactly("C", "R");
        // ".NET" inside a host name or "ASP.NET" is not a standalone ".NET" mention; "Node.js." at the end works
        assertThat(names(m.match("See example.net for details."))).isEmpty();
        assertThat(names(m.match("Backend in Node.js."))).containsExactly("Node.js");
    }

    @Test
    void shortNamesAreCaseSensitiveAndLongerOnesAreNot() {
        DictionarySkillMatcher m = matcher("SQL", "AWS", "PostgreSQL", "Kubernetes");
        assertThat(names(m.match("sql and aws are lower case here"))).isEmpty();
        assertThat(names(m.match("SQL on AWS"))).containsExactly("SQL", "AWS");
        assertThat(names(m.match("postgresql and KUBERNETES"))).containsExactly("PostgreSQL", "Kubernetes");
    }

    @Test
    void goMatchesOnlyInExactCase() {
        DictionarySkillMatcher m = matcher("Go", "Java");
        assertThat(m.match("Services in Go and Java.")).extracting(SkillMention::name).containsExactly("Go", "Java");
        assertThat(m.match("We go fast with Java.")).extracting(SkillMention::name).containsExactly("Java");
        assertThat(m.match("GO and Java.")).extracting(SkillMention::name).containsExactly("Java");
    }

    @Test
    void ambiguousNamesNeedContext() {
        DictionarySkillMatcher m = matcher("Go", "Swift", "Rust", "Java");
        assertThat(m.match("Go-getter with swift delivery.")).isEmpty();
        assertThat(m.match("Swift and Go, Rust too.")).as("ambiguous names don't confirm each other").isEmpty();
        assertThat(names(m.match("Go developer wanted."))).containsExactly("Go");
        assertThat(names(m.match("Experience with Swift."))).containsExactly("Swift");
        assertThat(names(m.match("Our stack: Rust."))).containsExactly("Rust");
        assertThat(names(m.match("Swift and Java."))).containsExactly("Swift", "Java");
        // context only counts in the same sentence
        assertThat(names(m.match("We use Java. Swift delivery matters."))).containsExactly("Java");
    }

    @Test
    void anAmbiguousSkillIsReportedAtItsFirstConfirmedMention() {
        DictionarySkillMatcher m = matcher("Go");
        String text = "Go-getter attitude. Our backend stack is Go.";
        List<SkillMention> found = m.match(text);
        assertThat(names(found)).containsExactly("Go");
        assertThat(found.get(0).offset()).isEqualTo(text.lastIndexOf("Go"));
    }

    @Test
    void theAmbiguousListIsConfigurable() {
        DictionarySkillMatcher m = matcher(List.of(), "Go", "Swift");
        assertThat(names(m.match("Go-getter with Swift delivery."))).containsExactly("Go", "Swift");
        DictionarySkillMatcher custom = matcher(List.of("java"), "Java");
        assertThat(custom.match("Java beans for breakfast.")).isEmpty();
        assertThat(names(custom.match("Java developer."))).containsExactly("Java");
    }

    @Test
    void aliasesResolveToTheSkill() {
        DictionarySkillMatcher m = matcher("PostgreSQL=Postgres,psql", "Kubernetes=K8s");
        List<SkillMention> found = m.match("Postgres on K8s; psql scripts.");
        assertThat(found).extracting(SkillMention::skillId).containsExactly(id("PostgreSQL"), id("Kubernetes"));
        assertThat(names(found)).as("the skill's own name is reported").containsExactly("PostgreSQL", "Kubernetes");
        assertThat(found.get(0).offset()).isZero();
    }

    @Test
    void eachSkillOnceOrderedByFirstMention() {
        DictionarySkillMatcher m = matcher("Java", "SQL", "Docker");
        String text = "Docker, SQL. Java and SQL and Docker again.";
        List<SkillMention> found = m.match(text);
        assertThat(names(found)).containsExactly("Docker", "SQL", "Java");
        assertThat(found).extracting(SkillMention::offset)
                .containsExactly(0, text.indexOf("SQL"), text.indexOf("Java"));
    }

    @Test
    void requiredComesFromTheHeuristic() {
        DictionarySkillMatcher m = matcher("Java", "SQL", "Kubernetes");
        List<SkillMention> found = m.match("You know Java and SQL. Kubernetes is advantageous.");
        assertThat(found).extracting(SkillMention::name, SkillMention::required)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Java", true),
                        org.assertj.core.groups.Tuple.tuple("SQL", true),
                        org.assertj.core.groups.Tuple.tuple("Kubernetes", false));
        assertThat(found.get(2).toRequirement()).isEqualTo(new SkillRequirement(id("Kubernetes"), "Kubernetes", false));
    }

    @Test
    void titleAndDescriptionAreBothSearched() {
        DictionarySkillMatcher m = matcher("Java", "SQL");
        assertThat(names(m.match("Java Engineer", "Daily SQL work."))).containsExactly("Java", "SQL");
        assertThat(names(m.match("Java Engineer", null))).containsExactly("Java");
        assertThat(names(m.match(null, "SQL"))).containsExactly("SQL");
    }

    @Test
    void emptyInputsAndBadTermsGiveNothing() {
        DictionarySkillMatcher m = matcher("Java");
        assertThat(m.match((String) null)).isEmpty();
        assertThat(m.match("   ")).isEmpty();
        assertThat(new DictionarySkillMatcher(List.of(), null).match("Java")).isEmpty();
        DictionarySkillMatcher withJunk = new DictionarySkillMatcher(
                java.util.Arrays.asList(null, new Term(null, "X", "X"), new Term(id("Y"), "Y", " "),
                        new Term(id("Java"), "Java", " Java ")), null);
        assertThat(withJunk.termCount()).isEqualTo(1);
        assertThat(names(withJunk.match("Java"))).containsExactly("Java");
    }

    @Test
    void regexCharactersInNamesAreLiteral() {
        DictionarySkillMatcher m = matcher("C++", "Objective-C", "A.I");
        assertThat(m.match("CXX and Objective C and AXI")).isEmpty();
        assertThat(names(m.match("Objective-C and C++"))).containsExactly("Objective-C", "C++");
    }
}
