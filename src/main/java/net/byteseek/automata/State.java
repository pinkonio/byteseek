/*
 * Copyright Matt Palmer 2009-2012, All rights reserved.
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
 */

package net.byteseek.automata;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.byteseek.utils.factory.DeepCopy;

/**
 * State is an interface representing a single state of an automata. A state can
 * have transitions to other states (or to itself), and is either a final or
 * non-final state.
 * <p>
 * A {@link Transition} is a reference to another State reachable from this
 * State. Transitions specify on which bytes a transition to the other State can
 * be made. To find which States are reachable given a byte, call
 * appendNextStatesForByte().
 * <p>
 * A final State is one which marks the completion of some part of the automata.
 * It is possible for a State to be both final and have transitions out of it to
 * other States. This is because an automata may continue to match after some
 * part of it has found a match.
 * <p>
 * For example, given the text 'AB' and using an automata which matches the
 * expression 'A'|'AB', the first 'A' will produce a match. So the State
 * transitioned to on the 'A' must be a final state. If matching continues, it
 * can produce another match on 'AB', making the State transitioned to on 'B'
 * (from the 'A' State) also a final state.
 * <p>
 * Finality implies that the automata could stop matching at this point, having
 * found a match of some sort. It does not imply that the automata cannot
 * continue to produce other matches afterwards by following outgoing
 * transitions.
 * <p>
 * It extends the {@link DeepCopy} interface, to ensure that all states can
 * provide deep copies of themselves.
 * 
 * @param <T>
 *            The type of objects the state will be associated with.
 * @see Transition
 * 
 * @author Matt Palmer
 */
public interface State<T> extends Iterable<Transition<T>>, DeepCopy {

	// -------------------------------------------------------------------------
	// Constants

	/** A constant representing a final state. */
	public static boolean	FINAL		= true;

	/** A constant representing a non-final state. */
	public static boolean	NON_FINAL	= false;

	// -------------------------------------------------------------------------
	// Methods

	/**
	 * Appends to the collection supplied any states reachable by the byte
	 * given.
	 * 
	 * @param value
	 *            The byte value to find the next states for.
	 * @param states
	 *            The collection to which the next states (if any) will be
	 *            added.
	 */
	public void appendNextStates(Collection<State<T>> states, byte value);

	/**
	 * Returns the first matching state for the give byte value, or null if no
	 * State can be transitioned to on that value.
	 * <p>
	 * This should only be used for Deterministic Finite-state Automata (DFA),
	 * which guarantees that there can be at most one State to follow for any
	 * given byte value. Other automata may transition to more than one State on
	 * a byte.
	 * 
	 * @param value
	 *            The byte value to get the next State for.
	 * @return The State to transition to for the byte value, or null if there
	 *         is no State to transition to.
	 */
	public State<T> getNextState(byte value);

	/**
	 * Returns true if this state is final.
	 * 
	 * @return if this State is final.
	 */
	public boolean isFinal();

	/**
	 * Sets whether this state is final or not.
	 * 
	 * @param isFinal
	 *            The finality of the state.
	 */
	public void setIsFinal(boolean isFinal);

	/**
	 * Returns true if the State is deterministic.  This means that there are no
	 * transitions to more than one state for the same byte value.
	 * <p>
	 * Any state with fewer than two transitions is deterministic by definition, as
	 * there can be no conflicting transitions.
	 * 
	 * @return true if the State is deterministic.
	 */
	public boolean isDeterministic();

	/**
	 * Returns an iterator over the transitions of the state.
	 * 
	 * @return An iterator over the transitions of the state.
	 */
	@Override
	public Iterator<Transition<T>> iterator();

	/**
	 * Returns a list of the transitions which currently exist in this State.0
	 * <p>
	 * Implementors of State guarantee that the contents of the list 
	 * returned will not subsequently change, even if the state itself
	 * is modified (new transitions added or removed from it), and equally,
	 * that changes to the list returned will not affect this State.  
	 * For example, an internal list is defensively copied.
	 * 
	 * @return A list of transitions from this state.
	 */
	public List<Transition<T>> getTransitions();

	/**
	 * Adds a {@link Transition} to this state.
	 * 
	 * @param transition
	 *            The transition to add to this state.
	 */
	public void addTransition(Transition<T> transition);

	/**
	 * Adds the list of {@link Transition}s to this state.
	 * 
	 * @param transitions
	 *            A list of transitions to add to this state.
	 */
	public void addAllTransitions(List<Transition<T>> transitions);

	/**
	 * Adds all the transitions returned by the iterator to this state.
	 * 
	 * @param transitionIterator
	 * 			  An iterator over transitions to add to this state.
	 */
	public void addAllTransitions(Iterator<Transition<T>> transitionIterator);

	/**
	 * Removes a {@link Transition} from this state.
	 * 
	 * @param transition
	 *            The transition to remove from this state.
	 * @return boolean Whether the transition was in the State.
	 */
	public boolean removeTransition(Transition<T> transition);

	/**
	 * Replaces a transition in the state with a new transition.
	 * 
	 * @param oldTransition The old transition to replace
	 * @param newTransition The new transition
	 * @return true if the transition was replaced.
	 */
	public boolean replaceTransition(Transition<T> oldTransition, Transition<T> newTransition);

	/**
	 * Clears all transitions from this state.
	 */
	public void clearTransitions();

	/**
	 * Adds an object of type T to the State.
	 * <p>
	 * This interface does not guarantee that the instance added will be unique.
	 * Specific implementations may provide this guarantee.
	 * 
	 * @param association
	 *            The object to associate with this state.
	 */
	public void addAssociation(T association);

	/**
	 * Adds all the associations of type T to the State.
	 * 
	 * @param associations
	 *            A collection of associations to add to the state.
	 */
	public void addAllAssociations(Collection<? extends T> associations);

	/**
	 * Adds all the associations of type T to the State.
	 * 
	 * @param associationIterator
	 *            An iterator over associations to add to the state.
	 */
	public void addAllAssociations(Iterator<T> associationIterator);

	/**
	 * Removes an object of type T from the State.
	 * <p>
	 * This interface does not guarantee that all instances will be removed,
	 * only the first encountered. Specific implementations may provide this
	 * guarantee.
	 * 
	 * @param object
	 *            The object to remove from the state.
	 * @return boolean true if the object was present in the State.
	 */
	public boolean removeAssociation(T object);

	/**
	 * Returns the current associations of type T.
	 * <p>
	 * No guarantee is made that the associations returned will be unique,
	 * although specific implementations may provide this guarantee.
	 * <p>
	 * Implementors of this interface guarantee that null will never be returned
	 * by this call. If there are no associations then an empty collection will
	 * be returned.
	 * <p>
	 * Implementors of State guarantee that the contents of the list 
	 * returned will not subsequently change, even if the state itself
	 * is modified (new associations added or removed from it), and equally,
	 * that changes to the list returned will not affect this State.
	 * 
	 * @return A collection of the objects currently associated with this state.
	 */
	public Collection<T> getAssociations();

	/**
	 * Returns an iterator over the associations of this State.
	 * 
	 * @return An iterator over the associations of this State.
	 */
	public Iterator<T> associationIterator();

	/**
	 * Sets a collection of objects of type T to be associated with this State.
	 * This method should ensure that only the associations passed in are
	 * associated with the state - any prior associations should be cleared.
	 * 
	 * @param associations
	 *            The objects to associated with this State.
	 */
	public void setAssociations(Collection<? extends T> associations);

	/**
	 * Clears any associations with this state.
	 */
	public void clearAssociations();

	/**
	 * Returns a deep copy of this state, its transitions
	 * and all the subsequent states reachable from this state.
	 * Associated objects are not deep copied, but the associations
	 * with them are.
	 * 
	 * @return State An automata which is a deep copy of the automata reachable from the state passed in,
	 *               with that state as its initial state.
	 *               
	 * @see #deepCopy(java.util.Map)
	 */
	public State<T> deepCopy();

	/**
	 * This method is inherited from the {@link DeepCopy} interface, and is
	 * redeclared here with a return type of State (rather than DeepCopy), to
	 * make using the method easier.
	 * 
	 * @param oldToNewObjects
	 *            A map of the original objects to their new deep copies.
	 * @return State A deep copy of this State and any Transitions and States
	 *         reachable from this State.
	 */
	@Override
	public State<T> deepCopy(Map<DeepCopy, DeepCopy> oldToNewObjects);

}
