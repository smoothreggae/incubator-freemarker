/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.freemarker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.freemarker.core.model.TemplateBooleanModel;
import org.apache.freemarker.core.model.TemplateCollectionModel;
import org.apache.freemarker.core.model.TemplateMethodModel;
import org.apache.freemarker.core.model.TemplateModel;
import org.apache.freemarker.core.model.TemplateModelException;
import org.apache.freemarker.core.model.TemplateModelIterator;
import org.apache.freemarker.core.model.TemplateScalarModel;
import org.apache.freemarker.core.model.TemplateSequenceModel;
import org.apache.freemarker.core.model.impl.SimpleScalar;
import org.apache.freemarker.core.util._StringUtil;


/**
 * Contains the string built-ins that correspond to basic regular expressions operations.
 */
class BuiltInsForStringsRegexp {

    static class groupsBI extends ASTExpBuiltIn {
        @Override
        TemplateModel _eval(Environment env) throws TemplateException {
            TemplateModel targetModel = target.eval(env);
            assertNonNull(targetModel, env);
            if (targetModel instanceof RegexMatchModel) {
                return ((RegexMatchModel) targetModel).getGroups();
            } else if (targetModel instanceof RegexMatchModel.MatchWithGroups) {
                return new NativeStringArraySequence(((RegexMatchModel.MatchWithGroups) targetModel).groups);

            } else {
                throw new UnexpectedTypeException(target, targetModel,
                        "regular expression matcher",
                        new Class[] { RegexMatchModel.class, RegexMatchModel.MatchWithGroups.class },
                        env);
            }
        }
    }
    
    static class matchesBI extends BuiltInForString {
        class MatcherBuilder implements TemplateMethodModel {
            
            String matchString;
            
            MatcherBuilder(String matchString) throws TemplateModelException {
                this.matchString = matchString;
            }
            
            @Override
            public TemplateModel execute(List<? extends TemplateModel> args) throws TemplateException {
                int argCnt = args.size();
                checkMethodArgCount(argCnt, 1, 2);
                
                String patternString = _CallableUtils.castArgToString(args, 0);
                long flags = argCnt > 1
                        ? RegexpHelper.parseFlagString(_CallableUtils.castArgToString(args, 1))
                        : 0;
                if ((flags & RegexpHelper.RE_FLAG_FIRST_ONLY) != 0) {
                    RegexpHelper.logFlagWarning("?" + key + " doesn't support the \"f\" flag.");
                }
                Pattern pattern = RegexpHelper.getPattern(patternString, (int) flags);
                return new RegexMatchModel(pattern, matchString);
            }
        }
        
        @Override
        TemplateModel calculateResult(String s, Environment env) throws TemplateModelException {
            return new MatcherBuilder(s);
        }
        
    }
    
    static class replace_reBI extends BuiltInForString {
        
        class ReplaceMethod implements TemplateMethodModel {
            private String s;

            ReplaceMethod(String s) {
                this.s = s;
            }

            @Override
            public TemplateModel execute(List<? extends TemplateModel> args) throws TemplateException {
                int argCnt = args.size();
                checkMethodArgCount(argCnt, 2, 3);
                String arg1 = _CallableUtils.castArgToString(args, 0);
                String arg2 = _CallableUtils.castArgToString(args, 1);
                long flags = argCnt > 2
                        ? RegexpHelper.parseFlagString(_CallableUtils.castArgToString(args, 2))
                        : 0;
                String result;
                if ((flags & RegexpHelper.RE_FLAG_REGEXP) == 0) {
                    RegexpHelper.checkNonRegexpFlags("replace", flags);
                    result = _StringUtil.replace(s, arg1, arg2,
                            (flags & RegexpHelper.RE_FLAG_CASE_INSENSITIVE) != 0,
                            (flags & RegexpHelper.RE_FLAG_FIRST_ONLY) != 0);
                } else {
                    Pattern pattern = RegexpHelper.getPattern(arg1, (int) flags);
                    Matcher matcher = pattern.matcher(s);
                    result = (flags & RegexpHelper.RE_FLAG_FIRST_ONLY) != 0
                            ? matcher.replaceFirst(arg2)
                            : matcher.replaceAll(arg2);
                } 
                return new SimpleScalar(result);
            }

        }
        
        @Override
        TemplateModel calculateResult(String s, Environment env) throws TemplateModelException {
            return new ReplaceMethod(s);
        }
        
    }
    
    // Represents the match
  
    static class RegexMatchModel 
    implements TemplateBooleanModel, TemplateCollectionModel, TemplateSequenceModel {
        static class MatchWithGroups implements TemplateScalarModel {
            final String matchedInputPart;
            final String[] groups;

            MatchWithGroups(String input, Matcher matcher) {
                matchedInputPart = input.substring(matcher.start(), matcher.end());
                final int grpCount = matcher.groupCount() + 1;
                groups = new String[grpCount];
                for (int i = 0; i < grpCount; i++) {
                    groups[i] = matcher.group(i);
                }
            }
            
            @Override
            public String getAsString() {
                return matchedInputPart;
            }
        }
        final Pattern pattern;
        
        final String input;
        private Matcher firedEntireInputMatcher;
        private Boolean entireInputMatched;
        
        private TemplateSequenceModel entireInputMatchGroups;
        
        private ArrayList matchingInputParts;
        
        RegexMatchModel(Pattern pattern, String input) {
            this.pattern = pattern;
            this.input = input;
        }
        
        @Override
        public TemplateModel get(int i) throws TemplateModelException {
            ArrayList matchingInputParts = this.matchingInputParts;
            if (matchingInputParts == null) {
                matchingInputParts = getMatchingInputPartsAndStoreResults();
            }
            return (TemplateModel) matchingInputParts.get(i);
        }
        
        @Override
        public boolean getAsBoolean() {
            Boolean result = entireInputMatched;
            return result != null ? result.booleanValue() : isEntrieInputMatchesAndStoreResults();
        }
        
        TemplateModel getGroups() {
           TemplateSequenceModel entireInputMatchGroups = this.entireInputMatchGroups;
           if (entireInputMatchGroups == null) {
               Matcher t = firedEntireInputMatcher;
               if (t == null) {
                   isEntrieInputMatchesAndStoreResults();
                   t = firedEntireInputMatcher;
               }
               final Matcher firedEntireInputMatcher = t;
               
                entireInputMatchGroups = new TemplateSequenceModel() {
                    
                    @Override
                    public TemplateModel get(int i) throws TemplateModelException {
                        try {
                            // Avoid IndexOutOfBoundsException:
                            if (i > firedEntireInputMatcher.groupCount()) {
                                return null;
                            }

                            return new SimpleScalar(firedEntireInputMatcher.group(i));
                        } catch (Exception e) {
                            throw new _TemplateModelException(e, "Failed to read match group");
                        }
                    }
                    
                    @Override
                    public int size() throws TemplateModelException {
                        try {
                            return firedEntireInputMatcher.groupCount() + 1;
                        } catch (Exception e) {
                            throw new _TemplateModelException(e, "Failed to get match group count");
                        }
                    }
                    
                };
                this.entireInputMatchGroups = entireInputMatchGroups;
            }
            return entireInputMatchGroups;
        }
        
        private ArrayList getMatchingInputPartsAndStoreResults() throws TemplateModelException {
            ArrayList matchingInputParts = new ArrayList();
            
            Matcher matcher = pattern.matcher(input);
            while (matcher.find()) {
                matchingInputParts.add(new MatchWithGroups(input, matcher));
            }
    
            this.matchingInputParts = matchingInputParts;
            return matchingInputParts;
        }
        
        private boolean isEntrieInputMatchesAndStoreResults() {
            Matcher matcher = pattern.matcher(input);
            boolean matches = matcher.matches();
            firedEntireInputMatcher = matcher;
            entireInputMatched = Boolean.valueOf(matches);
            return matches;
        }
        
        @Override
        public TemplateModelIterator iterator() {
            final ArrayList matchingInputParts = this.matchingInputParts;
            if (matchingInputParts == null) {
                final Matcher matcher = pattern.matcher(input);
                return new TemplateModelIterator() {
                    
                    private int nextIdx = 0;
                    boolean hasFindInfo = matcher.find();
                    
                    @Override
                    public boolean hasNext() {
                        final ArrayList matchingInputParts = RegexMatchModel.this.matchingInputParts;
                        if (matchingInputParts == null) {
                            return hasFindInfo;
                        } else {
                            return nextIdx < matchingInputParts.size();
                        }
                    }
                    
                    @Override
                    public TemplateModel next() throws TemplateModelException {
                        final ArrayList matchingInputParts = RegexMatchModel.this.matchingInputParts;
                        if (matchingInputParts == null) {
                            if (!hasFindInfo) throw new _TemplateModelException("There were no more matches");
                            MatchWithGroups result = new MatchWithGroups(input, matcher);
                            nextIdx++;
                            hasFindInfo = matcher.find();
                            return result;
                        } else {
                            try {
                                return (TemplateModel) matchingInputParts.get(nextIdx++);
                            } catch (IndexOutOfBoundsException e) {
                                throw new _TemplateModelException(e, "There were no more matches");
                            }
                        }
                    }
                    
                };
            } else {
                return new TemplateModelIterator() {
                    
                    private int nextIdx = 0;
                    
                    @Override
                    public boolean hasNext() {
                        return nextIdx < matchingInputParts.size();
                    }
                    
                    @Override
                    public TemplateModel next() throws TemplateModelException {
                        try {
                            return (TemplateModel) matchingInputParts.get(nextIdx++);
                        } catch (IndexOutOfBoundsException e) {
                            throw new _TemplateModelException(e, "There were no more matches");
                        }
                    }
                };
            }
        }
        
        @Override
        public int size() throws TemplateModelException {
            ArrayList matchingInputParts = this.matchingInputParts;
            if (matchingInputParts == null) {
                matchingInputParts = getMatchingInputPartsAndStoreResults();
            }
            return matchingInputParts.size();
        }
    }

    // Can't be instantiated
    private BuiltInsForStringsRegexp() { }
    
}
