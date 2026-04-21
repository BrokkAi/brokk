<?php
const TOP_LEVEL_CONST = 456;
define("OLD_STYLE_CONST", 789); // Not typically captured by tree-sitter queries for const_element
$topLevelVar = "test"; // Not typically captured as a "field" definition
