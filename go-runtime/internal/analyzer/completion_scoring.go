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
	start         int
	end           int
	contiguous    int
	wordStarts    int
	segmentStarts int
	gaps          int
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
	if m.pattern == "" || name == "" {
		return math.MaxInt
	}

	match, ok := m.match(name)
	if !ok {
		return math.MaxInt
	}
	if m.simpleLowercase && match.end < len(name) && name[match.end] == '$' {
		return math.MaxInt
	}

	degree := 0
	degree += match.wordStarts * 700
	degree += match.contiguous * 120
	degree += match.segmentStarts * 40
	degree -= match.gaps * 30
	degree -= match.start * 12
	degree -= len(name) - len(m.pattern)

	if match.start == 0 {
		degree += startMatchBonus
	}
	if match.start == 0 && match.end == len(name) && strings.EqualFold(m.pattern, name) {
		degree += exactMatchBonus
	}

	startProximityBonus := 400 - match.start*40
	if startProximityBonus > 0 {
		degree += startProximityBonus
	}

	return -degree
}

func (m fuzzyMatcher) match(name string) (fuzzyMatch, bool) {
	if !m.hasHumps {
		if index := strings.Index(strings.ToLower(name), m.lowerPattern); index >= 0 {
			wordStarts := 0
			for i := 0; i < len(m.pattern); i++ {
				if isWordStart(name, index+i) {
					wordStarts++
				}
			}
			return fuzzyMatch{
				start:         index,
				end:           index + len(m.pattern),
				contiguous:    len(m.pattern),
				wordStarts:    wordStarts,
				segmentStarts: 1,
				gaps:          0,
			}, true
		}
	}

	rawPatternRunes := []rune(m.pattern)
	patternRunes := []rune(strings.ToLower(m.pattern))
	nameRunes := []rune(name)
	lowerNameRunes := []rune(strings.ToLower(name))
	if len(patternRunes) > len(lowerNameRunes) {
		return fuzzyMatch{}, false
	}

	matchIndexes := make([]int, 0, len(patternRunes))
	from := 0
	for patternIndex, patternRune := range patternRunes {
		found := -1
		fallback := -1
		for i := from; i < len(lowerNameRunes); i++ {
			if lowerNameRunes[i] == patternRune {
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
				found = i
				break
			}
		}
		if found < 0 {
			found = fallback
		}
		if found < 0 {
			return fuzzyMatch{}, false
		}
		matchIndexes = append(matchIndexes, found)
		from = found + 1
	}

	wordStarts := 0
	contiguous := 1
	segments := 1
	gaps := 0
	bestContiguous := 1
	for i, idx := range matchIndexes {
		if isWordStartRunes(nameRunes, idx) {
			wordStarts++
		}
		if i == 0 {
			continue
		}
		delta := idx - matchIndexes[i-1]
		if delta == 1 {
			contiguous++
			if contiguous > bestContiguous {
				bestContiguous = contiguous
			}
			continue
		}
		gaps += delta - 1
		segments++
		contiguous = 1
	}

	return fuzzyMatch{
		start:         matchIndexes[0],
		end:           matchIndexes[len(matchIndexes)-1] + 1,
		contiguous:    bestContiguous,
		wordStarts:    wordStarts,
		segmentStarts: segments,
		gaps:          gaps,
	}, true
}

func hasHumps(value string) bool {
	for i, ch := range value {
		if i == 0 {
			continue
		}
		if ch >= 'A' && ch <= 'Z' {
			return true
		}
	}
	return false
}

func shouldPreferWordStart(ch rune) bool {
	return ch >= 'A' && ch <= 'Z'
}

func isSimpleLowercasePattern(value string) bool {
	if value == "" {
		return false
	}
	for _, ch := range value {
		if isWordSeparatorChar(ch) || ch == '*' {
			return false
		}
		if ch >= 'A' && ch <= 'Z' {
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
	if previous >= 'a' && previous <= 'z' && current >= 'A' && current <= 'Z' {
		return true
	}
	if (previous >= '0' && previous <= '9') != (current >= '0' && current <= '9') {
		return true
	}
	return false
}

func isWordSeparatorChar(ch rune) bool {
	switch ch {
	case '_', '-', ':', '+', '.', '$', ' ', '\t', '\n', '\r':
		return true
	default:
		return false
	}
}
