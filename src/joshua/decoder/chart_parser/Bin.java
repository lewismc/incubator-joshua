/* This file is part of the Joshua Machine Translation System.
 *
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.chart_parser;

import joshua.decoder.JoshuaConfiguration;
import joshua.decoder.Support;
import joshua.decoder.ff.FFDPState;
import joshua.decoder.ff.FFTransitionResult;
import joshua.decoder.ff.FeatureFunction;

import joshua.decoder.ff.tm.Rule;

import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.hypergraph.HyperEdge;
import joshua.decoder.hypergraph.HyperGraph;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * this class implement functions:
 * (1) combine small itesm into larger ones using rules, and create
 *     items and hyper-edges to construct a hyper-graph,
 * (2) evaluate model cost for items,
 * (3) cube-pruning
 * Note: Bin creates Items, but not all Items will be used in the
 * hyper-graph
 *
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class Bin {
	
	// remember the cost of the best item in the bin
	public double best_item_cost = IMPOSSIBLE_COST;
	
	// cutoff=best_item_cost+relative_threshold
	public double cut_off_cost = IMPOSSIBLE_COST;
	
	// num of corrupted items in this.heapItems, note that the
	// item in this.tableItems is always good
	int dead_items = 0;
	
	Chart p_chart = null;
	
	private int GOAL_SYM_ID;
	
	/* we need always maintain the priority queue (worst first),
	 * so that we can do prunning effieciently. On the other
	 * hand, we need the this.sortedItems only when necessary
	 */
	
	/* NOTE: MIN-HEAP, we put the worst-cost item at the top
	 * of the heap by manipulating the compare function
	 * this.heapItems: the only purpose is to help deecide which
	 * items should be removed from this.tableItems during
	 * pruning
	 */
	// TODO: initial capacity?
	private PriorityQueue<HGNode> heapItems =
		new PriorityQueue<HGNode>(1, HGNode.negtiveCostComparator);
	
	// to maintain uniqueness of items
	private HashMap<String,HGNode> tableItems =
		new HashMap<String,HGNode>();
	
	// signature by lhs
	private Map<Integer,SuperItem> tableSuperItems =
		new HashMap<Integer,SuperItem>();
	
	// sort values in tbl_item_signature, we need this list
	// whenever necessary
	private ArrayList<HGNode> sortedItems = null;
	
//===============================================================
// Static fields
//===============================================================
	
	private static final double EPSILON = 0.000001;
	private static final int IMPOSSIBLE_COST = 99999;
	
	private static final Logger logger = Logger.getLogger(Bin.class.getName());
	
	
//===============================================================
// Constructor
//===============================================================
	
	public Bin(Chart chart, int goalSymID) {
		this.p_chart     = chart;
		this.GOAL_SYM_ID = goalSymID;
	}
	
	
//===============================================================
// Methods
//===============================================================
	
	/* compute cost and the states of this item returned
	 * ArrayList: expectedTotalCost, finalizedTotalCost,
	 * transition_cost, bonus, list of states */
	
	public ComputeItemResult compute_item(
		Rule rule, ArrayList<HGNode> previousItems, int i, int j
	) {
		long startTime = Support.current_time(); // It's a lie, always == 0
		this.p_chart.n_called_compute_item++;
		
		double finalizedTotalCost = 0.0;
		
		//// See bug note in FeatureFunction about List vs ArrayList
		
		if (null != previousItems) {
			for (HGNode item : previousItems) {
				finalizedTotalCost += item.best_hyperedge.best_cost;
			}
		}
		
		HashMap<Integer,FFDPState> allItemStates = null;
		double transitionCostSum    = 0.0;
		double futureCostEstimation = 0.0;
		
		for (FeatureFunction ff : this.p_chart.p_l_models) {
			////long start2 = Support.current_time();
			if (ff.isStateful()) {
				//System.out.println("class name is " + ff.getClass().getName());
				FFTransitionResult state = HyperGraph.computeTransition(
					null, rule, previousItems, ff, i, j);
				
				transitionCostSum +=
					ff.getWeight() * state.getTransitionCost();
				
				futureCostEstimation +=
					ff.getWeight() * state.getFutureCostEstimation();
				
				FFDPState itemState = state.getStateForItem();
				if (null != itemState) {
					if (null == allItemStates) {
						allItemStates = new HashMap<Integer,FFDPState>();
					}
					allItemStates.put(ff.getFeatureID(), itemState);
				} else {
					throw new RuntimeException("compute_item: null getStateForItem()"
						+ "\n*"
						+ "\n* This will lead insidiously to a crash in"
						+ "\n* HyperGraph$Item.get_signature() since noone"
						+ "\n* checks invariant conditions before then."
						+ "\n*"
						+ "\n* Good luck tracking it down\n");
				}
			} else {
				FFTransitionResult state = HyperGraph.computeTransition(
					null, rule, previousItems, ff, i, j);
				
				transitionCostSum +=
					ff.getWeight() * state.getTransitionCost();
				
				futureCostEstimation += 0.0;
			}
			////ff.time_consumed += Support.current_time() - start2;
		}
		
		/* if we use this one (instead of compute transition
		 * cost on the fly, we will rely on the correctness
		 * of rule.statelesscost. This will cause a nasty
		 * bug for MERT. specifically, even we change the
		 * weight vector for features along the iteration,
		 * the HG cost does not reflect that as the Grammar
		 * is not reestimated!!! Of course, compute it on
		 * the fly will slow down the decoding (e.g., from
		 * 5 seconds to 6 seconds, for the example test
		 * set)
		 */
		//transitionCostSum += rule.getStatelessCost();
		
		finalizedTotalCost += transitionCostSum;
		double expectedTotalCost = finalizedTotalCost + futureCostEstimation;
		
		ComputeItemResult result = new ComputeItemResult();
		result.setExpectedTotalCost(expectedTotalCost);
		result.setFinalizedTotalCost(finalizedTotalCost);
		result.setTransitionTotalCost(transitionCostSum);
		result.setFeatDPStates(allItemStates);
		
		this.p_chart.g_time_compute_item += Support.current_time() - startTime;
		
		return result;
	}
	
	
	/* add all the items with GOAL_SYM state into the goal bin
	 * the goal bin has only one Item, which itself has many
	 * deductions only "goal bin" should call this function
	 */
	public void transit_to_goal(Bin bin) { // the bin[0][n], this is not goal bin
		this.sortedItems = new ArrayList<HGNode>();
		HGNode goalItem = null;
		
		for (HGNode item : bin.get_sorted_items()) {
			if (item.lhs == this.GOAL_SYM_ID) {
				double cost = item.best_hyperedge.best_cost;
				double finalTransitionCost = 0.0;
				
				for (FeatureFunction ff : this.p_chart.p_l_models) {
					finalTransitionCost +=
						ff.getWeight()
						* ff.finalTransition(item.getFeatDPState(ff));
				}
				
				ArrayList<HGNode> previousItems = new ArrayList<HGNode>();
				previousItems.add(item);
				
				HyperEdge dt = new HyperEdge(
					null, cost + finalTransitionCost, finalTransitionCost, previousItems);
				
				if (logger.isLoggable(Level.FINE)) {
					logger.fine(String.format(
						"Goal item, total_cost: %.3f; ant_cost: %.3f; final_tran: %.3f; ",
						cost + finalTransitionCost, cost, finalTransitionCost));
				}
				
				if (null == goalItem) {
					goalItem = new HGNode(
						0, this.p_chart.sent_len + 1, this.GOAL_SYM_ID, null, dt, cost + finalTransitionCost);
					this.sortedItems.add(goalItem);
				} else {
					goalItem.addHyperedgeInItem(dt);
					if (goalItem.best_hyperedge.best_cost > dt.best_cost) {
						goalItem.best_hyperedge = dt;
					}
				}
			} // End if item.lhs == this.GOAL_SYM_ID
		} // End foreach Item in bin.get_sorted_items()
		
		
		if (logger.isLoggable(Level.INFO)) {
			logger.info(String.format("Goal item, best cost is %.3f",
				goalItem.best_hyperedge.best_cost));
		}
		ensure_sorted();
		
		if (1 != get_sorted_items().size()) {
			throw new RuntimeException("the goal_bin does not have exactly one item");
		}
	}
	
	
	// axiom is for the zero-arity rules
	public void add_axiom(int i, int j, Rule rule, float latticeCost) {
		add_deduction_in_bin(
			compute_item(rule, null, i, j),
			rule, i, j, null, latticeCost);
	}
	
	
	/* add complete Items in Chart pruning inside this function */
	public void complete_cell(
		int i, int j, ArrayList<SuperItem> superItems,
		List<Rule> rules, int arity, float latticeCost
	) {
		//System.out.println(String.format("Complet_cell is called, n_rules: %d ", rules.size()));
		// consider all the possbile combinations (while
		// in Cube-pruning, we do not consider all the
		// possible combinations)
		for (Rule rule : rules) {
			if (1 == arity) {
				SuperItem super_ant1 = superItems.get(0);
				//System.out.println(String.format("Complet_cell, size %d ", super_ant1.l_items.size()));
				//rule.print_info(Support.DEBUG);
				for (HGNode antecedent: super_ant1.l_items) {
					ArrayList<HGNode> antecedents = new ArrayList<HGNode>();
					antecedents.add(antecedent);
					add_deduction_in_bin(
						compute_item(rule, antecedents, i, j),
						rule, i, j, antecedents, latticeCost);
				}
				
			} else if (arity == 2) {
				SuperItem super_ant1 = superItems.get(0);
				SuperItem super_ant2 = superItems.get(1);
				//System.out.println(String.format("Complet_cell, size %d * %d ", super_ant1.l_items.size(),super_ant2.l_items.size()));
				//rule.print_info(Support.DEBUG);
				for (HGNode it_ant1: super_ant1.l_items) {
					for (HGNode it_ant2: super_ant2.l_items) {
						//System.out.println(String.format("Complet_cell, ant1(%d, %d), ant2(%d, %d) ",it_ant1.i,it_ant1.j,it_ant2.i,it_ant2.j ));
						ArrayList<HGNode> antecedents = new ArrayList<HGNode>();
						antecedents.add(it_ant1);
						antecedents.add(it_ant2);
						add_deduction_in_bin(
							compute_item(rule, antecedents, i, j),
							rule, i, j, antecedents, latticeCost);
					}
				}
			} else {
				// BUG: We should fix this, as per the suggested implementation over email.
				throw new RuntimeException("Sorry, we can only deal with rules with at most TWO non-terminals");
			}
		}
	}
	
	
	/* add complete Items in Chart pruning inside this function */
	// TODO: our implementation do the prunining for each DotItem
	//       under each grammar, not aggregated as in the python
	//       version
	// TODO: the implementation is little bit different from
	//       the description in Liang'2007 ACL paper
	public void complete_cell_cube_prune(
		int i, int j, ArrayList<SuperItem> superItems,
		List<Rule> rules, float latticeCost
	) { // combinations: rules, antecent items
		// in the paper, heap_cands is called cand[v]
		PriorityQueue<CubePruneState> heap_cands =
			new PriorityQueue<CubePruneState>();
		
		// rememeber which state has been explored
		HashMap<String,Integer> cube_state_tbl = new HashMap<String,Integer>();
		
		if (null == rules || rules.size() <= 0) {
			return;
		}
		
		// seed the heap with best item
		Rule currentRule = rules.get(0);
		ArrayList<HGNode> currentAntecedents = new ArrayList<HGNode>();
		for (SuperItem si : superItems) {
			// TODO: si.l_items must be sorted
			currentAntecedents.add(si.l_items.get(0));
		}
		ComputeItemResult result =
			compute_item(currentRule, currentAntecedents, i, j);
		
		int[] ranks = new int[1+superItems.size()]; // rule, ant items
		for (int d = 0; d < ranks.length; d++) {
			ranks[d] = 1;
		}
		
		CubePruneState best_state =
			new CubePruneState(result, ranks, currentRule, currentAntecedents);
		heap_cands.add(best_state);
		cube_state_tbl.put(best_state.get_signature(),1);
		// cube_state_tbl.put(best_state,1);
		
		// extend the heap
		Rule   oldRule = null;
		HGNode oldItem = null;
		int    tem_c   = 0;
		while (heap_cands.size() > 0) {
			
			//========== decide if the top in the heap should be pruned
			tem_c++;
			CubePruneState cur_state = heap_cands.poll();
			currentRule = cur_state.rule;
			currentAntecedents = new ArrayList<HGNode>(cur_state.l_ants); // critical to create a new list
			//cube_state_tbl.remove(cur_state.get_signature()); // TODO, repeat
			add_deduction_in_bin(cur_state.tbl_item_states, cur_state.rule, i, j,cur_state.l_ants, latticeCost); // pre-pruning inside this function
			
			//if the best state is pruned, then all the remaining states should be pruned away
			if (cur_state.tbl_item_states.getExpectedTotalCost() > this.cut_off_cost + JoshuaConfiguration.fuzz1) {
				//n_prepruned += heap_cands.size();
				p_chart.n_prepruned_fuzz1 += heap_cands.size();
				break;
			}
			
			//========== extend the cur_state, and add the candidates into the heap
			for (int k = 0; k < cur_state.ranks.length; k++) {
				//GET new_ranks
				int[] new_ranks = new int[cur_state.ranks.length];
				for (int d = 0; d < cur_state.ranks.length; d++) {
					new_ranks[d] = cur_state.ranks[d];
				}
				new_ranks[k] = cur_state.ranks[k] + 1;
				
				String new_sig = CubePruneState.get_signature(new_ranks);
				
				if (cube_state_tbl.containsKey(new_sig) // explored before
				|| (k == 0 && new_ranks[k] > rules.size())
				|| (k != 0 && new_ranks[k] > superItems.get(k-1).l_items.size())
				) {
					continue;
				}
				
				if (k == 0) { // slide rule
					oldRule = currentRule;
					currentRule = rules.get(new_ranks[k]-1);
				} else { // slide ant
					oldItem = currentAntecedents.get(k-1); // conside k == 0 is rule
					currentAntecedents.set(k-1,
						superItems.get(k-1).l_items.get(new_ranks[k]-1));
				}
				
				CubePruneState t_state = new CubePruneState(
					compute_item(currentRule, currentAntecedents, i, j),
					new_ranks, currentRule, currentAntecedents);
				
				// add state into heap
				cube_state_tbl.put(new_sig,1);
				
				if (result.getExpectedTotalCost() < this.cut_off_cost + JoshuaConfiguration.fuzz2) {
					heap_cands.add(t_state);
				} else {
					//n_prepruned += 1;
					p_chart.n_prepruned_fuzz2 += 1;
				}
				// recover
				if (k == 0) { // rule
					currentRule = oldRule;
				} else { // ant
					currentAntecedents.set(k-1, oldItem);
				}
			}
		}
	}
	private static class CubePruneState implements Comparable<CubePruneState> {
		int[]             ranks;
		ComputeItemResult tbl_item_states;
		Rule              rule;
		ArrayList<HGNode> l_ants;
		
		public CubePruneState(ComputeItemResult state, int[] ranks, Rule rule, ArrayList<HGNode> antecedents) {
			this.tbl_item_states = state;
			this.ranks           = ranks;
			this.rule            = rule;
			// create a new vector is critical, because
			// currentAntecedents will change later
			this.l_ants = new ArrayList<HGNode>(antecedents);
		}
		
		
		private static String get_signature(int[] ranks2) {
			StringBuffer sb = new StringBuffer();
			if (null != ranks2) {
				for (int i = 0; i < ranks2.length; i++) {
					sb.append(' ').append(ranks2[i]);
				}
			}
			return sb.toString();
		}
		
		public String get_signature() {
			return get_signature(ranks);
		}
		
		//natual order by cost
		public int compareTo(CubePruneState that) {
			if (this.tbl_item_states.getExpectedTotalCost() < that.tbl_item_states.getExpectedTotalCost()) {
				return -1;
			} else if (this.tbl_item_states.getExpectedTotalCost() == that.tbl_item_states.getExpectedTotalCost()) {
				return 0;
			} else {
				return 1;
			}
		}
	}
	
	
	public HGNode add_deduction_in_bin(
		ComputeItemResult result, Rule rule, int i, int j,
		ArrayList<HGNode> ants, float latticeCost
	) {
		long start = Support.current_time();
		HGNode res = null;
		if (latticeCost != 0.0f) {
			rule = cloneAndAddLatticeCostIfNonZero(rule, latticeCost);
		}
		HashMap<Integer,FFDPState> item_state_tbl = result.getFeatDPStates();
		double expectedTotalCost  = result.getExpectedTotalCost(); // including outside estimation
		double transition_cost    = result.getTransitionTotalCost();
		double finalizedTotalCost = result.getFinalizedTotalCost();
		
		//double bonus = tbl_states.get(BONUS); // not used
		if (! should_prune(expectedTotalCost)) {
			HyperEdge dt = new HyperEdge(
				rule, finalizedTotalCost, transition_cost, ants);
			HGNode item = new HGNode(
				i, j, rule.getLHS(), item_state_tbl, dt, expectedTotalCost);
			add_deduction(item);
			
			if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("add an deduction with arity %d", rule.getArity()));
			
			res = item;
		} else {
			p_chart.n_prepruned++;
//			if (logger.isLoggable(Level.INFO)) logger.finest(String.format("Prepruned an deduction with arity %d", rule.getArity()));
			res = null;
		}
		p_chart.g_time_add_deduction += Support.current_time() - start;
		return res;
	}
	
	
//	 create a copy of the rule and set the lattice cost field
	//TODO:change this bad behavior
	private Rule cloneAndAddLatticeCostIfNonZero(Rule r, float latticeCost) {
		//!!!!!!!!!!!!!!!!!!!!!!!!!!! this is wrong, we need to fix this when one seriously incorporates lattice
		return r;
		/*if (latticeCost == 0.0f) {
			return r;
		} else {
			return new Rule(r.getLHS(), r.getFrench(), r.getEnglish(),r.getFeatureScores(), r.getArity(), r.getOwner(), latticeCost, r.getRuleID());
		}*/
	}
	
	
	/* each item has a list of deductions need to check whether the item is already exist, if yes, just add the deductions */
	private boolean add_deduction(HGNode newItem) {
		boolean res = false;
		HGNode oldItem = this.tableItems.get(newItem.getSignature());
		if (null != oldItem) { // have an item with same states, combine items
			p_chart.n_merged++;
			if (newItem.est_total_cost < oldItem.est_total_cost) {
				// the position of oldItem in the this.heapItems
				// may change, basically, we should remove the
				// oldItem, and re-insert it (linear time,
				// this is too expense)
				oldItem.is_dead = true; // this.heapItems.remove(oldItem);
				this.dead_items++;
				newItem.addHyperedgesInItem(oldItem.l_hyperedges);
				add_new_item(newItem); // this will update the HashMap, so that the oldItem is destroyed
				res = true;
			} else {
				oldItem.addHyperedgesInItem(newItem.l_hyperedges);
			}
		} else { // first time item
			p_chart.n_added++; // however, this item may not be used in the future due to pruning in the hyper-graph
			add_new_item(newItem);
			res = true;
		}
		this.cut_off_cost = Support.find_min(
			this.best_item_cost + JoshuaConfiguration.relative_threshold,
			IMPOSSIBLE_COST);
		run_pruning();
		return res;
	}
	
	
//	this function is called only there is no such item in the tbl
	private void add_new_item(HGNode item) {
		this.tableItems.put(item.getSignature(), item); // add/replace the item
		this.sortedItems = null; // reset the list
		this.heapItems.add(item);
		
		//since this.sortedItems == null, this is not necessary because we will always call ensure_sorted to reconstruct the this.tableSuperItems
		//add a super-items if necessary
		SuperItem si = this.tableSuperItems.get(item.lhs);
		if (null == si) {
			si = new SuperItem(item.lhs);
			this.tableSuperItems.put(item.lhs, si);
		}
		si.l_items.add(item);
		
		if (item.est_total_cost < this.best_item_cost) {
			this.best_item_cost = item.est_total_cost;
		}
	}
	
	
	public void print_info(Level level) {
		if (logger.isLoggable(level))
			logger.log(level,
				String.format("#### Stat of Bin, n_items=%d, n_super_items=%d",
					this.tableItems.size(), this.tableSuperItems.size()));
		
		ensure_sorted();
		for (HGNode it : this.sortedItems) {
			it.print_info(level);
		}
	}
	
	
	private boolean should_prune(double total_cost) {
		return (total_cost >= this.cut_off_cost);
	}
	
	
	private void run_pruning() {
		if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Pruning: heap size: %d; n_dead_items: %d", this.heapItems.size(),this.dead_items));
		if (this.heapItems.size() == this.dead_items) { // TODO:clear the heap, and reset this.dead_items??
			this.heapItems.clear();
			this.dead_items = 0;
			return;
		}
		while (this.heapItems.size() - this.dead_items > JoshuaConfiguration.max_n_items //bin limit pruning
		|| this.heapItems.peek().est_total_cost >= this.cut_off_cost) { // relative threshold pruning
			HGNode worstItem = this.heapItems.poll();
			if (worstItem.is_dead) { // clear the corrupted item
				this.dead_items--;
			} else {
				this.tableItems.remove(worstItem.getSignature()); // always make this.tableItems current
				this.p_chart.n_pruned++;
//				if (logger.isLoggable(Level.INFO)) logger.info(String.format("Run_pruning: %d; cutoff=%.3f, realcost: %.3f",p_chart.n_pruned,this.cut_off_cost,worstItem.est_total_cost));
			}
		}
		if (this.heapItems.size() - this.dead_items == JoshuaConfiguration.max_n_items) { // TODO:??
			this.cut_off_cost = Support.find_min(
				this.cut_off_cost,
				this.heapItems.peek().est_total_cost + EPSILON);
		}
	}
	
	
	/* get a sorted list of Items in the bin, and also make
	 * sure the list of items in any SuperItem is sorted, this
	 * will be called only necessary, which means that the list
	 * is not always sorted mainly needed for goal_bin and
	 * cube-pruning
	 */
	private void ensure_sorted() {
		if (null == this.sortedItems) {
			//get a sorted items ArrayList
			Object[] t_col = this.tableItems.values().toArray();
			Arrays.sort(t_col);
			this.sortedItems = new ArrayList<HGNode>();
			for (int c = 0; c < t_col.length;c++) {
				this.sortedItems.add((HGNode)t_col[c]);
			}
			//TODO: we cannot create new SuperItem here because the DotItem link to them
			
			//update this.tableSuperItems
			ArrayList<SuperItem> tem_list =
				new ArrayList<SuperItem>(this.tableSuperItems.values());
			for (SuperItem t_si : tem_list) {
				t_si.l_items.clear();
			}
			
			for (HGNode it : this.sortedItems) {
				SuperItem si = this.tableSuperItems.get(it.lhs);
				if (null == si) { // sanity check
					throw new RuntimeException("Does not have super Item, have to exist");
				}
				si.l_items.add(it);
			}
			
			ArrayList<Integer> to_remove = new ArrayList<Integer>();
			//note: some SuperItem may not contain any items any more due to pruning
			for (Integer k : this.tableSuperItems.keySet()) {
				if (this.tableSuperItems.get(k).l_items.size() <= 0) {
					to_remove.add(k); // note that: we cannot directly do the remove, because it will throw ConcurrentModificationException
					//System.out.println("have zero items in superitem " + k);
					//this.tableSuperItems.remove(k);
				}
			}
			for (Integer t : to_remove) {
				this.tableSuperItems.remove(t);
			}
		}
	}
	
	
	public ArrayList<HGNode> get_sorted_items() {
		ensure_sorted();
		return this.sortedItems;
	}
	
	public Map<Integer,SuperItem> get_sorted_super_items() {
		ensure_sorted();
		return this.tableSuperItems;
	}
	
	
	/* list of items that have the same lhs but may have different LM states */
	public static class SuperItem {
		int lhs; // state
		ArrayList<HGNode> l_items = new ArrayList<HGNode>();
		
		public SuperItem(int lhs) {
			this.lhs = lhs;
		}
	}
	
	public static class ComputeItemResult {
		private double expectedTotalCost;
		private double finalizedTotalCost;
		private double transitionTotalCost;
		// the key is feature id; tbl of dpstate for each stateful feature
		private HashMap<Integer,FFDPState> tbl_feat_dpstates;
		
		public void setExpectedTotalCost(double cost) {
			this.expectedTotalCost = cost;
		}
		
		public double getExpectedTotalCost() {
			return this.expectedTotalCost;
		}
		
		public void setFinalizedTotalCost(double cost) {
			this.finalizedTotalCost = cost;
		}
		
		public double getFinalizedTotalCost() {
			return this.finalizedTotalCost;
		}
		
		public void setTransitionTotalCost(double cost) {
			this.transitionTotalCost = cost;
		}
		
		public double getTransitionTotalCost() {
			return this.transitionTotalCost;
		}
		
		public void setFeatDPStates(HashMap<Integer,FFDPState> states) {
			this.tbl_feat_dpstates = states;
		}
		
		public HashMap<Integer,FFDPState> getFeatDPStates() {
			return this.tbl_feat_dpstates;
		}
	}
}
