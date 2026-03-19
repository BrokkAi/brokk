package analyzer

import (
	"math"
	"strings"
)

const (
	exactMatchBonus     = 5000
	startMatchBonus     = 2000
	classTypeBonus      = 10000
	packageDepthPenalty = 10
)

type fuzzyMatcher struct {
	pattern         string
	lowerPattern    string
	simpleLowercase bool
	hasHumps        bool
}

type fuzzyMatch struct {
	indexes []int
}

func newFuzzyMatcher(pattern string) fuzzyMatcher {
	trimmed := strings.TrimSpace(pattern)
	trimmed = strings.TrimRight(trimmed, "*")
	return fuzzyMatcher{
		pattern:         trimmed,
		lowerPattern:    strings.ToLower(trimmed),
		simpleLowercase: isSimpleLowercasePattern(trimmed),
		hasHumps:        hasHumps(trimmed),
	}
}

func (m fuzzyMatcher) score(name string) int {
	if m.pattern == "" {
		if name == "" {
			return 0
		}
		return math.MaxInt
	}
	if name == "" {
		return math.MaxInt
	}

	match, ok := m.match(name)
	if !ok || len(match.indexes) == 0 {
		return math.MaxInt
	}

	nameRunes := []rune(name)
	if m.simpleLowercase && endsBeforeSeparator(nameRunes, match.indexes) {
		return math.MaxInt
	}

	patternRunes := []rune(m.pattern)
	score := calculateMatchScore(nameRunes, patternRunes, match.indexes)

	startIndex := match.indexes[0]
	if startIndex == 0 {
		score += startMatchBonus
	}

	if isExactCaseInsensitiveMatch(nameRunes, patternRunes, match.indexes) {
		score += exactMatchBonus
	}

	startProximityBonus := 400 - startIndex*40
	if startProximityBonus > 0 {
		score += startProximityBonus
	}

	return -score
}

func (m fuzzyMatcher) matches(name string) bool {
	return m.score(name) != math.MaxInt
}

func (m fuzzyMatcher) match(name string) (fuzzyMatch, bool) {
	nameRunes := []rune(name)
	if !m.hasHumps {
		if index := strings.Index(strings.ToLower(name), m.lowerPattern); index >= 0 {
			indexes := make([]int, 0, len([]rune(m.pattern)))
			for i := 0; i < len([]rune(m.pattern)); i++ {
				indexes = append(indexes, index+i)
			}
			return fuzzyMatch{indexes: indexes}, true
		}
	}

	patternRunes := []rune(strings.ToLower(m.pattern))
	rawPatternRunes := []rune(m.pattern)
	lowerNameRunes := []rune(strings.ToLower(name))
	if len(patternRunes) > len(lowerNameRunes) {
		return fuzzyMatch{}, false
	}

	indexes := make([]int, 0, len(patternRunes))
	from := 0
	for patternIndex, patternRune := range patternRunes {
		found := -1
		fallback := -1
		wordStart := -1
		for i := from; i < len(lowerNameRunes); i++ {
			if lowerNameRunes[i] != patternRune {
				continue
			}
			if isWordStartRunes(nameRunes, i) && wordStart < 0 {
				wordStart = i
			}
			if shouldPreferWordStart(rawPatternRunes[patternIndex]) {
				if isWordStartRunes(nameRunes, i) {
					found = i
					break
				}
				if fallback < 0 {
					fallback = i
				}
				continue
			}
			if found < 0 {
				found = i
			}
		}
		if !m.hasHumps && found >= 0 && patternIndex > 0 && wordStart >= 0 && wordStart > found {
			found = wordStart
		}
		if found < 0 {
			found = fallback
		}
		if !m.hasHumps && found < 0 && patternIndex > 0 {
			found = wordStart
		}
		if found < 0 {
			return fuzzyMatch{}, false
		}
		indexes = append(indexes, found)
		from = found + 1
	}

	return fuzzyMatch{indexes: indexes}, true
}

func calculateMatchScore(nameRunes []rune, patternRunes []rune, indexes []int) int {
	if len(indexes) == 0 {
		return math.MinInt
	}

	firstStart := indexes[0]
	fragments := countFragments(indexes)
	wordStart := firstStart == 0 || isWordStartRunes(nameRunes, firstStart)
	startMatch := firstStart == 0
	finalMatch := indexes[len(indexes)-1] == len(nameRunes)-1
	skippedHumps := countSkippedHumps(nameRunes, indexes)
	matchingCaseScore := evaluateMatchingCase(nameRunes, patternRunes, indexes)

	unmatchedTail := len(nameRunes) - indexes[len(indexes)-1] - 1
	unmatchedPenalty := 0
	if fragments == 1 {
		unmatchedPenalty = unmatchedTail * 2
	}

	score := 0
	if wordStart {
		score += 1000
	}
	score += matchingCaseScore
	score -= fragments
	score -= skippedHumps * 20
	score -= totalGapLength(indexes)
	score += 2
	if startMatch {
		score++
	}
	score -= unmatchedPenalty
	if finalMatch {
		score++
	}
	return score
}

func evaluateMatchingCase(nameRunes []rune, patternRunes []rune, indexes []int) int {
	matchingCaseScore := 0
	humpStartMatchedUpperCase := false
	for i, index := range indexes {
		afterGap := i > 0 && indexes[i] != indexes[i-1]+1
		isHumpStart := isWordStartRunes(nameRunes, index)
		nameChar := nameRunes[index]
		patternChar := patternRunes[i]

		if isHumpStart {
			humpStartMatchedUpperCase = nameChar == patternChar && isUpperCaseRune(patternChar)
		}

		if afterGap && isHumpStart && isLowerCaseRune(patternChar) {
			matchingCaseScore -= 10
			continue
		}
		if nameChar == patternChar {
			switch {
			case isUpperCaseRune(patternChar):
				matchingCaseScore += 50
			case index == 0:
				matchingCaseScore++
			case isHumpStart:
				matchingCaseScore++
			}
			continue
		}
		if isHumpStart || (isLowerCaseRune(patternChar) && humpStartMatchedUpperCase) {
			matchingCaseScore--
		}
	}
	return matchingCaseScore
}

func countFragments(indexes []int) int {
	if len(indexes) == 0 {
		return 0
	}
	fragments := 1
	for i := 1; i < len(indexes); i++ {
		if indexes[i] != indexes[i-1]+1 {
			fragments++
		}
	}
	return fragments
}

func countSkippedHumps(nameRunes []rune, indexes []int) int {
	skipped := 0
	for i := 1; i < len(indexes); i++ {
		if indexes[i] == indexes[i-1]+1 {
			continue
		}
		for j := indexes[i-1] + 1; j < indexes[i]; j++ {
			if isWordStartRunes(nameRunes, j) {
				skipped++
			}
		}
	}
	return skipped
}

func totalGapLength(indexes []int) int {
	total := 0
	for i := 1; i < len(indexes); i++ {
		if indexes[i] > indexes[i-1]+1 {
			total += indexes[i] - indexes[i-1] - 1
		}
	}
	return total
}

func isExactCaseInsensitiveMatch(nameRunes []rune, patternRunes []rune, indexes []int) bool {
	if len(indexes) != len(patternRunes) || len(indexes) == 0 {
		return false
	}
	if indexes[0] != 0 || indexes[len(indexes)-1] != len(nameRunes)-1 {
		return false
	}
	return strings.EqualFold(string(patternRunes), string(nameRunes))
}

func endsBeforeSeparator(nameRunes []rune, indexes []int) bool {
	if len(indexes) == 0 {
		return false
	}
	end := indexes[len(indexes)-1] + 1
	if end+1 >= len(nameRunes) {
		return false
	}
	return isWordSeparatorChar(nameRunes[end]) && (isUpperCaseRune(nameRunes[end+1]) || isDigitRune(nameRunes[end+1]))
}

func hasHumps(value string) bool {
	for i, ch := range value {
		if i == 0 {
			continue
		}
		if isUpperCaseRune(ch) {
			return true
		}
	}
	return false
}

func shouldPreferWordStart(ch rune) bool {
	return isUpperCaseRune(ch)
}

func isSimpleLowercasePattern(value string) bool {
	if value == "" {
		return false
	}
	for _, ch := range value {
		if isWordSeparatorChar(ch) || ch == '*' {
			return false
		}
		if isUpperCaseRune(ch) {
			return false
		}
	}
	return true
}

func isWordStart(name string, index int) bool {
	return isWordStartRunes([]rune(name), index)
}

func isWordStartRunes(name []rune, index int) bool {
	if index <= 0 {
		return true
	}
	current := name[index]
	previous := name[index-1]
	if isWordSeparatorChar(previous) {
		return true
	}
	if isLowerCaseRune(previous) && isUpperCaseRune(current) {
		return true
	}
	if isDigitRune(previous) != isDigitRune(current) {
		return true
	}
	return false
}

func isWordSeparatorChar(ch rune) bool {
	switch ch {
	case '_', '-', ':', '+', '.', '$', '/', '\\', ' ', '\t', '\n', '\r':
		return true
	default:
		return false
	}
}

func isUpperCaseRune(ch rune) bool {
	return ch >= 'A' && ch <= 'Z'
}

func isLowerCaseRune(ch rune) bool {
	return ch >= 'a' && ch <= 'z'
}

func isDigitRune(ch rune) bool {
	return ch >= '0' && ch <= '9'
}
