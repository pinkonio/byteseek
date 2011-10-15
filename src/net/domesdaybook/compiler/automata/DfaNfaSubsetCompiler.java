/*
 * Copyright Matt Palmer 2009-2011, All rights reserved.
 *
 * This code is licensed under a standard 3-clause BSD license:
 *
 * 
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice, 
 *    this list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright notice, 
 *    this list of conditions and the following disclaimer in the documentation 
 *    and/or other materials provided with the distribution.
 * 
 *  * The names of its contributors may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 *  
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package net.domesdaybook.compiler.automata;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.domesdaybook.automata.State;
import net.domesdaybook.automata.Transition;
import net.domesdaybook.automata.AutomataUtils;
import net.domesdaybook.automata.state.StateFactory;
import net.domesdaybook.automata.state.SimpleStateFactory;
import net.domesdaybook.automata.transition.TransitionFactory;
import net.domesdaybook.automata.transition.TransitionSingleByteMatcherFactory;
import net.domesdaybook.compiler.CompileException;
import net.domesdaybook.compiler.Compiler;

/**
 *
 * @author matt
 */
public final class DfaNfaSubsetCompiler implements Compiler<State, State> {

    private static DfaNfaSubsetCompiler defaultCompiler;
    public static State dfaFromNfa(State nfaInitialState) throws CompileException {
        defaultCompiler = new DfaNfaSubsetCompiler();
        return defaultCompiler.compile(nfaInitialState);
    }
    
    
    private final Compiler<State, String> nfaCompiler;
    private final StateFactory StateFactory;
    private final TransitionFactory transitionFactory;
    
    public DfaNfaSubsetCompiler() {
        this(null, null, null);
    }

    public DfaNfaSubsetCompiler(final StateFactory stateFactory) {
        this(null, stateFactory, null);
    }

    public DfaNfaSubsetCompiler(final TransitionFactory transitionFactory) {
        this(null, null, transitionFactory);
    }
    
    public DfaNfaSubsetCompiler(final Compiler<State, String> nfaCompilerToUse) {
        this(nfaCompilerToUse, null, null);
    }
    
    public DfaNfaSubsetCompiler(final Compiler<State, String> nfaCompilerToUse, final StateFactory stateFactory) { 
        this(nfaCompilerToUse, stateFactory, null);
    }
    
    public DfaNfaSubsetCompiler(final Compiler<State, String> nfaCompilerToUse, final TransitionFactory transitionFactory) {
        this(nfaCompilerToUse, null, transitionFactory);
    }

    public DfaNfaSubsetCompiler(Compiler<State, String> nfaCompilerToUse, StateFactory StateFactoryToUse, TransitionFactory factoryToUse) {
        if (nfaCompilerToUse == null) {
            nfaCompiler = new NfaExpressionCompiler();
        } else {
            nfaCompiler = nfaCompilerToUse;
        }
        if (StateFactoryToUse == null) {
            StateFactory = new SimpleStateFactory();
        } else {
            StateFactory = StateFactoryToUse;
        }
        if (factoryToUse == null) {
            transitionFactory = new TransitionSingleByteMatcherFactory();
        } else {
            transitionFactory = factoryToUse;
        }
    }


    @Override
    public State compile(State nfaToTransform) {
        Map<Set<State>, State> nfaToDfa = new IdentityHashMap<Set<State>, State>();
        Set<State> initialState = new HashSet<State>();
        initialState.add(nfaToTransform);
        return getState(initialState, nfaToDfa);
    }


    private State getState(Set<State> States, Map<Set<State>, State> StatesToDfa) {
        // This method is called recursively -
        // if we have already built this dfa state, just return it:
        if (StatesToDfa.containsKey(States)) {
            return StatesToDfa.get(States);
        } else {
            return createState(States, StatesToDfa);
        }
    }


    private State createState(Set<State> sourceStates, Map<Set<State>, State> StatesToDfa) {

        // Determine if the new Dfa state should be final:
        boolean isFinal = anyStatesAreFinal(sourceStates);

        // Create the new state and register it in our map of nfa states to dfa state.
        State newState = StateFactory.create(isFinal);
        StatesToDfa.put(sourceStates, newState);

        // Create transitions to all the new dfa states this one points to:
        createDfaTransitions(sourceStates, newState, StatesToDfa);

        // Set the state strategy to first transition to match (if it matches in
        // a DFA, then there can be no other transition which will match):
        newState.setTransitionStrategy(State.FIRST_MATCHING_TRANSITION);
        
        return newState;
    }


   private void createDfaTransitions(Set<State> sourceStates, State newState, Map<Set<State>, State> StatesToDfa)  {
       // For each target nfa state set, add a transition on those bytes:
       Map<Set<State>, Set<Byte>> targetStatesToBytes = getDfaTransitionInfo(sourceStates);
       for (Set<State> targetState : targetStatesToBytes.keySet()) {

            // Get the set of bytes to transition on:
            Set<Byte> transitionBytes = targetStatesToBytes.get(targetState);

            // Recursive: get the target DFA state for this transition.
            State targetDFAState = getState(targetState, StatesToDfa);

            // Create a transition to the target state using the bytes to transition on:
            // This places a burden on the implementor of createSetTransition to ensure it
            // returns an efficient transition, given the set of bytes passed to it.
            // Maybe should rename method or add a createOptimalTransition() method...?
            final Transition transition = transitionFactory.createSetTransition(transitionBytes, false, targetDFAState);

            // Add the transition to the source state:
            newState.addTransition(transition);
        }
   }

   
   private Map<Set<State>, Set<Byte>> getDfaTransitionInfo(Set<State> sourceStates) {
        // Build a map of bytes to the target nfa states each points to:
        Map<Byte, Set<State>> byteToStates = buildByteToStates(sourceStates);

        // Return a map of target nfa states to the bytes they each transition on:
        return AutomataUtils.getStatesToBytes(byteToStates);
   }
   

   private Map<Byte, Set<State>> buildByteToStates(final Set<State> states) {
        Map<Byte, Set<State>> byteToTargetStates = new LinkedHashMap<Byte, Set<State>>();
        for (final State state : states) {
            AutomataUtils.buildByteToStates(state, byteToTargetStates);
        }
        return byteToTargetStates;
    }


    private boolean anyStatesAreFinal(Set<State> sourceStates) {
        boolean isFinal = false;
        for (State state : sourceStates) {
            if (state.isFinal()) {
                isFinal = true;
                break;
            }
        }
        return isFinal;
    }
   


}
