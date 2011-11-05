/*
 * Copyright Matt Palmer 2009-2011, All rights reserved.
 *
 * This code is licensed under a standard 3-clause BSD license:
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

package net.domesdaybook.automata;

import java.util.Collection;
import java.util.Map;
import net.domesdaybook.automata.strategy.IterateTransitions;
import net.domesdaybook.object.copy.DeepCopy;

/**
 * A TransitionStrategy is an interface for classes which choose which distinct 
 * {@link State}s can be transitioned to, given a byte to transition on and a list of
 * {@link Transition}s.  
 * <p>
 * Different transition strategies are appropriate for different kinds of automata.
 * For example, a Deterministic Finite State Automata (DFA) has a unique state for  
 * any byte it can transition on from any other state, so a strategy which picks 
 * the first state it can find for a byte is appropriate (@link FirstMatchingTransition}. 
 * On the other hand, a Non-deterministic Finite State Automata (NFA) can transition 
 * to more than one State on a given byte, so the {@link IterateTransitions} 
 * strategy is more appropriate.  If a state has no outgoing transitions at all, 
 * then the {@link NoTransitions} strategy is appropriate.  
 * <p>
 * However, no matter what the overall type of the automata, any given State can
 * use a transition strategy which works for its particular mix of transitions. 
 * For example, a state with only one outgoing transition can obviously use the 
 * FirstMatchingTransition strategy, even if the state is part of an NFA.  For
 * maximum efficiency, the most appropriate transition strategy should be picked
 * for each State (once you are sure that the State will not have further transitions 
 * added or removed).
 * <p>
 * It supports two usage models:
 * <ul>
 * <li>Stateless strategy.
 * <li>Stateful strategy.
 * </ul>
 * When used as a stateless strategy (pun on State not intended), the TransitionStrategy
 * has no knowledge of any particular State, or indeed, any internal state at all.
 * It is a pure strategy-pattern object.  The same instance can be re-used by many
 * different State implementations. 
 * <p>
 * The other usage model involves the TransitionStrategy building some kind of optimised
 * State lookup for the particular State it is used in, probably when it is constructed.
 * Such a TransitionStrategy is bound to the State it is used in, and cannot be 
 * shared across States.  In this case, the TransitionStrategy will probably ignore 
 * the list of transitions passed in to its getDistinctStatesForByte() method, 
 * relying instead on its internal lookup for the transitions of its parent State
 * built at the time it was initialised.
 * <p>
 * Note that in the stateful case, any Transitions added to or removed from
 * the parent State after the TransitionStrategy is set may cause the State to
 * automatically select a different Transition strategy.  Therefore,
 * these types of TransitionStrategy should only be set once the State will have
 * no further changes to its transitions.
 * <p>
 * It extends the {@link DeepCopy} interface, to ensure that all TransitionStrategies
 * can provide deep copies of themselves.  Note that if they are stateless, then
 * they can return themselves.
 * 
 * @see State
 * @see Transition
 * @see net.domesdaybook.automata.strategy.FirstMatchingTransition
 * @see net.domesdaybook.automata.strategy.IterateTransitions
 * @see net.domesdaybook.automata.strategy.NoTransition
 * @see net.domesdaybook.object.copy.DeepCopy
 * @see <a href="http://en.wikipedia.org/wiki/Strategy_pattern">Strategy pattern</a>
 * 
 * @author matt
 */
public interface TransitionStrategy extends DeepCopy {
    
    /**
     * Appends a set of distinct states which can be transitioned to given a byte
     * and a collection of transitions.  
     * <p>
     * If the strategy is stateful, the collection of transitions passed in
     * may be ignored in favour of looking up the transitions in a pre-built
     * data structure.
     * <P>
     * This method can process both deterministic and non-deterministic automata,
     * although it may not be as efficient as getNextState() for deterministic ones.
     * 
     * @param states The collection of states to append to.
     * @param value The byte which we want to transition on.
     * @param transitions A collection of transitions which this strategy may
     * interrogate to determine the states (if it is a stateless strategy)
     */
    void appendDistinctStatesForByte(Collection<State> states, byte value, Collection<Transition> transitions);

    
    /**
     * Returns the first State which can be transitioned to on a byte, or null
     * if there is no such State.  
     * <p>
     * If the strategy is stateful, the collection of transitions passed in
     * may be ignored in favour of looking up the transitions in a pre-built
     * data structure.
     * <p>
     * This method is only suitable for processing Deterministic Finite-state
     * Automata (DFA), which guarantees there is at most one State that can
     * transition to on a given byte value.
     * 
     * @param value The byte value to transition on
     * @param transitions A collection of transitions to transition on.
     * 
     * @return State The first matching state which can be transitioned to on 
     * the byte value, or null if no such State exists.
     */
    public State getFirstMatchingState(byte value, Collection<Transition> transitions);
    
    
    /**
     * This method is inherited from the {@link DeepCopy} interface,
     * and is redeclared here with a return type of TransitionStrategy
     * (rather than DeepCopy), to make using the method easier.
     *
     * @param oldToNewObjects A map of the original objects to their new deep copies.
     * @return TransitionStrategy A deep copy of this TransitionStrategy.  If the TransitionStrategy
     * is stateless, it may return itself. If it is stateful, it should ensure that
     * all the States it references are also deep copied.
     */    
    TransitionStrategy deepCopy(Map<DeepCopy, DeepCopy> oldToNewObjects);

    
    
}
