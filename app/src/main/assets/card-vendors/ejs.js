/* Recovered EJS 3.1.10, Apache-2.0. See THIRD_PARTY_NOTICES.md. */
(() => {
var Dc, Zc = {}, Kc = {};
var $c = {
  resolve: (...parts) => new URL(parts.join('/'), 'https://cards.xujing.invalid/').pathname,
  dirname: value => String(value).replace(/[^/]*$/, ''),
  extname: value => /\.[^/.]*$/.exec(String(value))?.[0] || '',
  existsSync: () => false,
  readFileSync: () => { throw Error('File templates are unavailable; use an explicit EJS includer'); }
};
function Jc() {
            return (
              Dc ||
                ((Dc = 1),
                (function (e) {
                  var t = /[|\\{}()[\]^$+*?.]/g,
                    n = Object.prototype.hasOwnProperty,
                    r = function (e, t) {
                      return n.apply(e, [t]);
                    };
                  e.escapeRegExpChars = function (e) {
                    return e ? String(e).replace(t, "\\$&") : "";
                  };
                  var a = {
                      "&": "&amp;",
                      "<": "&lt;",
                      ">": "&gt;",
                      '"': "&#34;",
                      "'": "&#39;",
                    },
                    i = /[&<>'"]/g;
                  function o(e) {
                    return a[e] || e;
                  }
                  function s() {
                    return (
                      Function.prototype.toString.call(this) +
                      ';\nvar _ENCODE_HTML_RULES = {\n      "&": "&amp;"\n    , "<": "&lt;"\n    , ">": "&gt;"\n    , \'"\': "&#34;"\n    , "\'": "&#39;"\n    }\n  , _MATCH_HTML = /[&<>\'"]/g;\nfunction encode_char(c) {\n  return _ENCODE_HTML_RULES[c] || c;\n};\n'
                    );
                  }
                  e.escapeXML = function (e) {
                    return null == e ? "" : String(e).replace(i, o);
                  };
                  try {
                    "function" == typeof Object.defineProperty
                      ? Object.defineProperty(e.escapeXML, "toString", {
                          value: s,
                        })
                      : (e.escapeXML.toString = s);
                  } catch (l) {
                    console.warn(
                      "Unable to set escapeXML.toString (is the Function prototype frozen?)",
                    );
                  }
                  ((e.shallowCopy = function (e, t) {
                    if (((t = t || {}), null != e))
                      for (var n in t)
                        r(t, n) &&
                          "__proto__" !== n &&
                          "constructor" !== n &&
                          (e[n] = t[n]);
                    return e;
                  }),
                    (e.shallowCopyFromList = function (e, t, n) {
                      if (((n = n || []), (t = t || {}), null != e))
                        for (var a = 0; a < n.length; a++) {
                          var i = n[a];
                          if (void 0 !== t[i]) {
                            if (!r(t, i)) continue;
                            if ("__proto__" === i || "constructor" === i)
                              continue;
                            e[i] = t[i];
                          }
                        }
                      return e;
                    }),
                    (e.cache = {
                      _data: {},
                      set: function (e, t) {
                        this._data[e] = t;
                      },
                      get: function (e) {
                        return this._data[e];
                      },
                      remove: function (e) {
                        delete this._data[e];
                      },
                      reset: function () {
                        this._data = {};
                      },
                    }),
                    (e.hyphenToCamel = function (e) {
                      return e.replace(/-[a-z]/g, function (e) {
                        return e[1].toUpperCase();
                      });
                    }),
                    (e.createNullProtoObjWherePossible =
                      "function" == typeof Object.create
                        ? function () {
                            return Object.create(null);
                          }
                        : { __proto__: null } instanceof Object
                          ? function () {
                              return {};
                            }
                          : function () {
                              return { __proto__: null };
                            }),
                    (e.hasOwnOnlyObject = function (t) {
                      var n = e.createNullProtoObjWherePossible();
                      for (var a in t) r(t, a) && (n[a] = t[a]);
                      return n;
                    }));
                })(Kc)),
              Kc
            );
          }
          var Yc,
            Xc = "3.1.10";
          var Qc =
              (Yc ||
                ((Yc = 1),
                (function (e) {
                  /**
                   * @file Embedded JavaScript templating engine. {@link http://ejs.co}
                   * @author Matthew Eernisse <mde@fleegix.org>
                   * @author Tiancheng "Timothy" Gu <timothygu99@gmail.com>
                   * @project EJS
                   * @license {@link http://www.apache.org/licenses/LICENSE-2.0 Apache License, Version 2.0}
                   */
                  var t = $c,
                    n = $c,
                    r = Jc(),
                    a = !1,
                    i = Xc,
                    o = "locals",
                    s = [
                      "delimiter",
                      "scope",
                      "context",
                      "debug",
                      "compileDebug",
                      "client",
                      "_with",
                      "rmWhitespace",
                      "strict",
                      "filename",
                      "async",
                    ],
                    l = s.concat("cache"),
                    c = /^\uFEFF/,
                    u = /^[a-zA-Z_$][0-9a-zA-Z_$]*$/;
                  function d(n, r) {
                    var a;
                    if (
                      r.some(function (r) {
                        return (
                          (a = e.resolveInclude(n, r, !0)),
                          t.existsSync(a)
                        );
                      })
                    )
                      return a;
                  }
                  function f(t, n) {
                    var r,
                      a = t.filename,
                      i = arguments.length > 1;
                    if (t.cache) {
                      if (!a)
                        throw new Error("cache option requires a filename");
                      if ((r = e.cache.get(a))) return r;
                      i || (n = p(a).toString().replace(c, ""));
                    } else if (!i) {
                      if (!a)
                        throw new Error(
                          "Internal EJS error: no file name or template provided",
                        );
                      n = p(a).toString().replace(c, "");
                    }
                    return (
                      (r = e.compile(n, t)),
                      t.cache && e.cache.set(a, r),
                      r
                    );
                  }
                  function p(t) {
                    return e.fileLoader(t);
                  }
                  function h(n, a) {
                    var i = r.shallowCopy(
                      r.createNullProtoObjWherePossible(),
                      a,
                    );
                    if (
                      ((i.filename = (function (n, r) {
                        var a,
                          i,
                          o = r.views,
                          s = /^[A-Za-z]+:\\|^\//.exec(n);
                        if (s && s.length)
                          ((n = n.replace(/^\/*/, "")),
                            (a = Array.isArray(r.root)
                              ? d(n, r.root)
                              : e.resolveInclude(n, r.root || "/", !0)));
                        else if (
                          (r.filename &&
                            ((i = e.resolveInclude(n, r.filename)),
                            t.existsSync(i) && (a = i)),
                          !a && Array.isArray(o) && (a = d(n, o)),
                          !a && "function" != typeof r.includer)
                        )
                          throw new Error(
                            'Could not find the include file "' +
                              r.escapeFunction(n) +
                              '"',
                          );
                        return a;
                      })(n, i)),
                      "function" == typeof a.includer)
                    ) {
                      var o = a.includer(n, i.filename);
                      if (
                        o &&
                        (o.filename && (i.filename = o.filename), o.template)
                      )
                        return f(i, o.template);
                    }
                    return f(i);
                  }
                  function m(e, t, n, r, a) {
                    var i = t.split("\n"),
                      o = Math.max(r - 3, 0),
                      s = Math.min(i.length, r + 3),
                      l = a(n),
                      c = i
                        .slice(o, s)
                        .map(function (e, t) {
                          var n = t + o + 1;
                          return (n == r ? " >> " : "    ") + n + "| " + e;
                        })
                        .join("\n");
                    throw (
                      (e.path = l),
                      (e.message =
                        (l || "ejs") + ":" + r + "\n" + c + "\n\n" + e.message),
                      e
                    );
                  }
                  function g(e) {
                    return e.replace(/;(\s*$)/, "$1");
                  }
                  function v(t, n) {
                    var a = r.hasOwnOnlyObject(n),
                      i = r.createNullProtoObjWherePossible();
                    ((this.templateText = t),
                      (this.mode = null),
                      (this.truncate = !1),
                      (this.currentLine = 1),
                      (this.source = ""),
                      (i.client = a.client || !1),
                      (i.escapeFunction =
                        a.escape || a.escapeFunction || r.escapeXML),
                      (i.compileDebug = !1 !== a.compileDebug),
                      (i.debug = !!a.debug),
                      (i.filename = a.filename),
                      (i.openDelimiter =
                        a.openDelimiter || e.openDelimiter || "<"),
                      (i.closeDelimiter =
                        a.closeDelimiter || e.closeDelimiter || ">"),
                      (i.delimiter = a.delimiter || e.delimiter || "%"),
                      (i.strict = a.strict || !1),
                      (i.context = a.context),
                      (i.cache = a.cache || !1),
                      (i.rmWhitespace = a.rmWhitespace),
                      (i.root = a.root),
                      (i.includer = a.includer),
                      (i.outputFunctionName = a.outputFunctionName),
                      (i.localsName = a.localsName || e.localsName || o),
                      (i.views = a.views),
                      (i.async = a.async),
                      (i.destructuredLocals = a.destructuredLocals),
                      (i.legacyInclude =
                        void 0 === a.legacyInclude || !!a.legacyInclude),
                      i.strict
                        ? (i._with = !1)
                        : (i._with = void 0 === a._with || a._with),
                      (this.opts = i),
                      (this.regex = this.createRegex()));
                  }
                  ((e.cache = r.cache),
                    (e.fileLoader = t.readFileSync),
                    (e.localsName = o),
                    (e.promiseImpl = new Function("return this;")().Promise),
                    (e.resolveInclude = function (e, t, r) {
                      var a = n.dirname,
                        i = n.extname,
                        o = (0, n.resolve)(r ? t : a(t), e);
                      return (i(e) || (o += ".ejs"), o);
                    }),
                    (e.compile = function (e, t) {
                      return (
                        t &&
                          t.scope &&
                          (a ||
                            (console.warn(
                              "`scope` option is deprecated and will be removed in EJS 3",
                            ),
                            (a = !0)),
                          t.context || (t.context = t.scope),
                          delete t.scope),
                        new v(e, t).compile()
                      );
                    }),
                    (e.render = function (e, t, n) {
                      var a = t || r.createNullProtoObjWherePossible(),
                        i = n || r.createNullProtoObjWherePossible();
                      return (
                        2 == arguments.length && r.shallowCopyFromList(i, a, s),
                        f(i, e)(a)
                      );
                    }),
                    (e.renderFile = function () {
                      var t,
                        n,
                        a,
                        i = Array.prototype.slice.call(arguments),
                        o = i.shift(),
                        s = { filename: o };
                      return (
                        "function" == typeof arguments[arguments.length - 1] &&
                          (t = i.pop()),
                        i.length
                          ? ((n = i.shift()),
                            i.length
                              ? r.shallowCopy(s, i.pop())
                              : (n.settings &&
                                  (n.settings.views &&
                                    (s.views = n.settings.views),
                                  n.settings["view cache"] && (s.cache = !0),
                                  (a = n.settings["view options"]) &&
                                    r.shallowCopy(s, a)),
                                r.shallowCopyFromList(s, n, l)),
                            (s.filename = o))
                          : (n = r.createNullProtoObjWherePossible()),
                        (function (t, n, r) {
                          var a;
                          if (!r) {
                            if ("function" == typeof e.promiseImpl)
                              return new e.promiseImpl(function (e, r) {
                                try {
                                  e((a = f(t)(n)));
                                } catch (i) {
                                  r(i);
                                }
                              });
                            throw new Error(
                              "Please provide a callback function",
                            );
                          }
                          try {
                            a = f(t)(n);
                          } catch (i) {
                            return r(i);
                          }
                          r(null, a);
                        })(s, n, t)
                      );
                    }),
                    (e.Template = v),
                    (e.clearCache = function () {
                      e.cache.reset();
                    }),
                    (v.modes = {
                      EVAL: "eval",
                      ESCAPED: "escaped",
                      RAW: "raw",
                      COMMENT: "comment",
                      LITERAL: "literal",
                    }),
                    (v.prototype = {
                      createRegex: function () {
                        var e = "(<%%|%%>|<%=|<%-|<%_|<%#|<%|%>|-%>|_%>)",
                          t = r.escapeRegExpChars(this.opts.delimiter),
                          n = r.escapeRegExpChars(this.opts.openDelimiter),
                          a = r.escapeRegExpChars(this.opts.closeDelimiter);
                        return (
                          (e = e
                            .replace(/%/g, t)
                            .replace(/</g, n)
                            .replace(/>/g, a)),
                          new RegExp(e)
                        );
                      },
                      compile: function () {
                        var e,
                          t,
                          a,
                          i = this.opts,
                          o = "",
                          s = "",
                          l = i.escapeFunction,
                          c = i.filename
                            ? JSON.stringify(i.filename)
                            : "undefined";
                        if (!this.source) {
                          if (
                            (this.generateSource(),
                            (o +=
                              '  var __output = "";\n  function __append(s) { if (s !== undefined && s !== null) __output += s }\n'),
                            i.outputFunctionName)
                          ) {
                            if (!u.test(i.outputFunctionName))
                              throw new Error(
                                "outputFunctionName is not a valid JS identifier.",
                              );
                            o +=
                              "  var " +
                              i.outputFunctionName +
                              " = __append;\n";
                          }
                          if (i.localsName && !u.test(i.localsName))
                            throw new Error(
                              "localsName is not a valid JS identifier.",
                            );
                          if (
                            i.destructuredLocals &&
                            i.destructuredLocals.length
                          ) {
                            for (
                              var d =
                                  "  var __locals = (" +
                                  i.localsName +
                                  " || {}),\n",
                                f = 0;
                              f < i.destructuredLocals.length;
                              f++
                            ) {
                              var p = i.destructuredLocals[f];
                              if (!u.test(p))
                                throw new Error(
                                  "destructuredLocals[" +
                                    f +
                                    "] is not a valid JS identifier.",
                                );
                              (f > 0 && (d += ",\n  "),
                                (d += p + " = __locals." + p));
                            }
                            o += d + ";\n";
                          }
                          (!1 !== i._with &&
                            ((o += "  with (" + i.localsName + " || {}) {\n"),
                            (s += "  }\n")),
                            (s += "  return __output;\n"),
                            (this.source = o + this.source + s));
                        }
                        ((e = i.compileDebug
                          ? "var __line = 1\n  , __lines = " +
                            JSON.stringify(this.templateText) +
                            "\n  , __filename = " +
                            c +
                            ";\ntry {\n" +
                            this.source +
                            "} catch (e) {\n  rethrow(e, __lines, __filename, __line, escapeFn);\n}\n"
                          : this.source),
                          i.client &&
                            ((e =
                              "escapeFn = escapeFn || " +
                              l.toString() +
                              ";\n" +
                              e),
                            i.compileDebug &&
                              (e =
                                "rethrow = rethrow || " +
                                m.toString() +
                                ";\n" +
                                e)),
                          i.strict && (e = '"use strict";\n' + e),
                          i.debug && console.log(e),
                          i.compileDebug &&
                            i.filename &&
                            (e = e + "\n//# sourceURL=" + c + "\n"));
                        try {
                          if (i.async)
                            try {
                              a = new Function(
                                "return (async function(){}).constructor;",
                              )();
                            } catch (Tr) {
                              throw Tr instanceof SyntaxError
                                ? new Error(
                                    "This environment does not support async/await",
                                  )
                                : Tr;
                            }
                          else a = Function;
                          t = new a(
                            i.localsName + ", escapeFn, include, rethrow",
                            e,
                          );
                        } catch (Tr) {
                          throw (
                            Tr instanceof SyntaxError &&
                              (i.filename &&
                                (Tr.message += " in " + i.filename),
                              (Tr.message += " while compiling ejs\n\n"),
                              (Tr.message +=
                                "If the above error is not helpful, you may want to try EJS-Lint:\n"),
                              (Tr.message +=
                                "https://github.com/RyanZim/EJS-Lint"),
                              i.async ||
                                ((Tr.message += "\n"),
                                (Tr.message +=
                                  "Or, if you meant to create an async function, pass `async: true` as an option."))),
                            Tr
                          );
                        }
                        var g = i.client
                          ? t
                          : function (e) {
                              return t.apply(i.context, [
                                e || r.createNullProtoObjWherePossible(),
                                l,
                                function (t, n) {
                                  var a = r.shallowCopy(
                                    r.createNullProtoObjWherePossible(),
                                    e,
                                  );
                                  return (
                                    n && (a = r.shallowCopy(a, n)),
                                    h(t, i)(a)
                                  );
                                },
                                m,
                              ]);
                            };
                        if (
                          i.filename &&
                          "function" == typeof Object.defineProperty
                        ) {
                          var v = i.filename,
                            b = n.basename(v, n.extname(v));
                          try {
                            Object.defineProperty(g, "name", {
                              value: b,
                              writable: !1,
                              enumerable: !1,
                              configurable: !0,
                            });
                          } catch (Tr) {}
                        }
                        return g;
                      },
                      generateSource: function () {
                        (this.opts.rmWhitespace &&
                          (this.templateText = this.templateText
                            .replace(/[\r\n]+/g, "\n")
                            .replace(/^\s+|\s+$/gm, "")),
                          (this.templateText = this.templateText
                            .replace(/[ \t]*<%_/gm, "<%_")
                            .replace(/_%>[ \t]*/gm, "_%>")));
                        var e = this,
                          t = this.parseTemplateText(),
                          n = this.opts.delimiter,
                          r = this.opts.openDelimiter,
                          a = this.opts.closeDelimiter;
                        t &&
                          t.length &&
                          t.forEach(function (i, o) {
                            var s;
                            if (
                              0 === i.indexOf(r + n) &&
                              0 !== i.indexOf(r + n + n) &&
                              (s = t[o + 2]) != n + a &&
                              s != "-" + n + a &&
                              s != "_" + n + a
                            )
                              throw new Error(
                                'Could not find matching close tag for "' +
                                  i +
                                  '".',
                              );
                            e.scanLine(i);
                          });
                      },
                      parseTemplateText: function () {
                        for (
                          var e,
                            t = this.templateText,
                            n = this.regex,
                            r = n.exec(t),
                            a = [];
                          r;
                        )
                          (0 !== (e = r.index) &&
                            (a.push(t.substring(0, e)), (t = t.slice(e))),
                            a.push(r[0]),
                            (t = t.slice(r[0].length)),
                            (r = n.exec(t)));
                        return (t && a.push(t), a);
                      },
                      _addOutput: function (e) {
                        if (
                          (this.truncate &&
                            ((e = e.replace(/^(?:\r\n|\r|\n)/, "")),
                            (this.truncate = !1)),
                          !e)
                        )
                          return e;
                        ((e = (e = (e = (e = e.replace(/\\/g, "\\\\")).replace(
                          /\n/g,
                          "\\n",
                        )).replace(/\r/g, "\\r")).replace(/"/g, '\\"')),
                          (this.source += '    ; __append("' + e + '")\n'));
                      },
                      scanLine: function (e) {
                        var t,
                          n = this.opts.delimiter,
                          r = this.opts.openDelimiter,
                          a = this.opts.closeDelimiter;
                        switch (((t = e.split("\n").length - 1), e)) {
                          case r + n:
                          case r + n + "_":
                            this.mode = v.modes.EVAL;
                            break;
                          case r + n + "=":
                            this.mode = v.modes.ESCAPED;
                            break;
                          case r + n + "-":
                            this.mode = v.modes.RAW;
                            break;
                          case r + n + "#":
                            this.mode = v.modes.COMMENT;
                            break;
                          case r + n + n:
                            ((this.mode = v.modes.LITERAL),
                              (this.source +=
                                '    ; __append("' +
                                e.replace(r + n + n, r + n) +
                                '")\n'));
                            break;
                          case n + n + a:
                            ((this.mode = v.modes.LITERAL),
                              (this.source +=
                                '    ; __append("' +
                                e.replace(n + n + a, n + a) +
                                '")\n'));
                            break;
                          case n + a:
                          case "-" + n + a:
                          case "_" + n + a:
                            (this.mode == v.modes.LITERAL && this._addOutput(e),
                              (this.mode = null),
                              (this.truncate =
                                0 === e.indexOf("-") || 0 === e.indexOf("_")));
                            break;
                          default:
                            if (this.mode) {
                              switch (this.mode) {
                                case v.modes.EVAL:
                                case v.modes.ESCAPED:
                                case v.modes.RAW:
                                  e.lastIndexOf("//") > e.lastIndexOf("\n") &&
                                    (e += "\n");
                              }
                              switch (this.mode) {
                                case v.modes.EVAL:
                                  this.source += "    ; " + e + "\n";
                                  break;
                                case v.modes.ESCAPED:
                                  this.source +=
                                    "    ; __append(escapeFn(" + g(e) + "))\n";
                                  break;
                                case v.modes.RAW:
                                  this.source +=
                                    "    ; __append(" + g(e) + ")\n";
                                  break;
                                case v.modes.COMMENT:
                                  break;
                                case v.modes.LITERAL:
                                  this._addOutput(e);
                              }
                            } else this._addOutput(e);
                        }
                        this.opts.compileDebug &&
                          t &&
                          ((this.currentLine += t),
                          (this.source +=
                            "    ; __line = " + this.currentLine + "\n"));
                      },
                    }),
                    (e.escapeXML = r.escapeXML),
                    (e.__express = e.renderFile),
                    (e.VERSION = i),
                    (e.name = "ejs"),
                    "undefined" != typeof window && (window.ejs = e));
                })(Zc)),
              Zc),
             unused = null;
window.ejs = Qc;
})();
