import type { Plugin } from 'svelte-exmarkdown';
import rehypeShikiFromHighlighter from '@shikijs/rehype/core';
import { createHighlighterCore } from 'shiki/core';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';
import { createCssVariablesTheme } from 'shiki/core';

// Initial languages and themes
import js from 'shiki/langs/javascript.mjs';
import ts from 'shiki/langs/typescript.mjs';
import python from 'shiki/langs/python.mjs';
import java from 'shiki/langs/java.mjs';
import bash from 'shiki/langs/bash.mjs';
import actionscript from 'shiki/langs/actionscript-3.mjs';
import c from 'shiki/langs/c.mjs';
import clojure from 'shiki/langs/clojure.mjs';
import cpp from 'shiki/langs/cpp.mjs';
import csharp from 'shiki/langs/csharp.mjs';
import css from 'shiki/langs/css.mjs';
import csv from 'shiki/langs/csv.mjs';
import d from 'shiki/langs/d.mjs';
import dart from 'shiki/langs/dart.mjs';
import pascal from 'shiki/langs/pascal.mjs';
import dockerfile from 'shiki/langs/dockerfile.mjs';
import f90 from 'shiki/langs/f90.mjs';
import go from 'shiki/langs/go.mjs';
import groovy from 'shiki/langs/groovy.mjs';
import handlebars from 'shiki/langs/handlebars.mjs';
import html from 'shiki/langs/html.mjs';
import ini from 'shiki/langs/ini.mjs';
import json from 'shiki/langs/json.mjs';
import jsonc from 'shiki/langs/jsonc.mjs';
import kotlin from 'shiki/langs/kotlin.mjs';
import latex from 'shiki/langs/latex.mjs';
import less from 'shiki/langs/less.mjs';
import lisp from 'shiki/langs/lisp.mjs';
import lua from 'shiki/langs/lua.mjs';
import makefile from 'shiki/langs/makefile.mjs';
import markdown from 'shiki/langs/markdown.mjs';
import perl from 'shiki/langs/perl.mjs';
import php from 'shiki/langs/php.mjs';
import proto from 'shiki/langs/proto.mjs';
import properties from 'shiki/langs/properties.mjs';
import ruby from 'shiki/langs/ruby.mjs';
import rust from 'shiki/langs/rust.mjs';
import sas from 'shiki/langs/sas.mjs';
import scala from 'shiki/langs/scala.mjs';
import sql from 'shiki/langs/sql.mjs';
import tcl from 'shiki/langs/tcl.mjs';
import vb from 'shiki/langs/vb.mjs';
import xml from 'shiki/langs/xml.mjs';
import yaml from 'shiki/langs/yaml.mjs';

// Define a CSS variables theme
const cssVarsTheme = createCssVariablesTheme({
  name: 'css-vars',
  variablePrefix: '--shiki-',
  variableDefaults: {},
  fontStyle: true
});

// Custom transformer to add data-language attribute dynamically
const languageAttributeTransformer = {
  name: 'add-language-attributes',
  pre(node) {
    node.properties = node.properties || {};
    node.properties['data-language'] = this.options.lang;
    return node;
  }
};

// Singleton promise for the Shiki plugin
export const shikiPluginPromise: Promise<Plugin> = createHighlighterCore({
  themes: [cssVarsTheme],
  langs: [
    js, ts, python, java, bash,
    actionscript, c, clojure, cpp, csharp, css, csv, d, dart, pascal,
    dockerfile, f90, go, groovy, handlebars, html, ini, json, jsonc,
    kotlin, latex, less, lisp, lua, makefile, markdown, perl, php,
    proto, properties, ruby, rust, sas, scala, sql, tcl, vb, xml, yaml
  ],
  engine: createJavaScriptRegexEngine({
    target: 'ES2018',
    forgiving: true
  }),
}).then(highlighter => ({
  rehypePlugin: [
    rehypeShikiFromHighlighter,
    highlighter,
    {
      theme: 'css-vars',
      colorsRendering: 'css-vars',
      transformers: [languageAttributeTransformer]
    }
  ]
}));


